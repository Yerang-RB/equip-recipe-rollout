package com.rollout.worker

import com.rollout.domain.EquipmentStatus
import com.rollout.domain.HistoryResult
import com.rollout.job.AppliedRecipe
import com.rollout.job.ClaimedTask
import com.rollout.job.FailureClassifier
import com.rollout.job.FailureKind
import com.rollout.job.HistoryRepository
import com.rollout.job.LeaseHeartbeat
import com.rollout.job.TaskClaimer
import com.rollout.job.TaskOutcomeService
import com.rollout.job.WorkerProperties
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** task 실행에 필요한 것 전부(설비·레시피) — 한 번에 읽어 온다. */
private data class TaskWork(
	val equipmentStatus: String,
	val consoleUrl: String,
	val consoleUser: String,
	val consolePassword: String,
	val partNo: String,
	val version: Int,
	val paramsJson: String,
)

/**
 * 워커의 본체(설계 §5): 클레임 → 세마포어 → 어댑터 → 결과 기록. 클레임 실행 풀은 세마포어보다 크다 —
 * 실제 상한은 브라우저 세션 수여야 하고(불변 조건 8), 그래야 DB 층과 워커 층이 따로 재진다.
 */
@Component
@ConditionalOnProperty(name = ["mode"], havingValue = "worker")
class ClaimLoop(
	private val claimer: TaskClaimer,
	private val outcomes: TaskOutcomeService,
	private val heartbeat: LeaseHeartbeat,
	private val history: HistoryRepository,
	private val applier: RecipeApplier,
	private val jdbc: JdbcClient,
	private val props: WorkerProperties,
	private val clock: Clock,
	private val meters: MeterRegistry,
) : SmartLifecycle {

	private val log = LoggerFactory.getLogger(javaClass)
	private val workerId = "worker-${UUID.randomUUID().toString().take(8)}"

	/**
	 * 소유권(`claimed_by`)은 프로세스가 아니라 **시도** 단위다 — 클레임 스레드들이 id 하나를 나눠 쓰면 리스를 회수한
	 * 같은 프로세스의 다른 스레드와 늦게 깨어난 원래 스레드를 DB가 가르지 못한다(리뷰 B6).
	 */
	private val claimSeq = AtomicLong()
	private val browserSlots = Semaphore(props.browserConcurrency)

	/** 마지막 클레임 시도 시각 — 「루프가 살아 있는가」의 신호다(`ClaimLoopHealthIndicator`). */
	@Volatile private var lastIteration: Instant = clock.instant()

	@Volatile private var running = false
	private var claimPool: ExecutorService? = null
	private var heartbeats: ScheduledExecutorService? = null

	override fun start() {
		running = true
		heartbeats = Executors.newSingleThreadScheduledExecutor()
		val pool = Executors.newFixedThreadPool(props.claimParallelism)
		repeat(props.claimParallelism) { pool.submit(::loop) }
		claimPool = pool
		log.info("워커 기동: id={} 클레임 병렬 {} 브라우저 상한 {}", workerId, props.claimParallelism, props.browserConcurrency)
	}

	override fun stop() {
		running = false
		claimPool?.shutdownNow()
		heartbeats?.shutdownNow()
		claimPool?.awaitTermination(10, TimeUnit.SECONDS)
	}

	override fun isRunning(): Boolean = running

	private fun loop() {
		while (running) {
			try {
				if (!iterateOnce()) Thread.sleep(POLL_MILLIS)
			} catch (e: InterruptedException) {
				return
			} catch (e: Exception) {
				// 여기까지 온 예외는 클레임·기록 경로의 사고다. 쥔 task는 리스 만료로 회수된다(그것이 복구다).
				log.error("클레임 루프 반복 실패: {}", e.javaClass.simpleName, e)
				runCatching { Thread.sleep(POLL_MILLIS) }.onFailure { return }
			}
		}
	}

	fun lastIterationAt(): Instant = lastIteration

	/** 한 번 집어 끝까지 처리한다. 집을 것이 없으면 false. */
	fun iterateOnce(): Boolean {
		lastIteration = clock.instant()
		outcomes.reapExhausted()
		val owner = "$workerId-${claimSeq.incrementAndGet()}"
		val task = claimer.claim(owner) ?: return false
		execute(task, owner)
		return true
	}

	private fun execute(task: ClaimedTask, owner: String) {
		val started = Timer.start(meters)
		MDC.put("job_id", task.jobId.toString())
		MDC.put("task_id", task.id.toString())
		// scheduleAtFixedRate는 작업이 예외를 던지면 이후 실행을 전부 취소한다 — 일시적 DB 오류 한 번으로
		// 리스 갱신이 영구히 멈추고 작업이 리스보다 길어지면 다른 워커가 회수해 같은 설비에 두 번 쓴다(메타 리뷰 주의 2).
		val beat = heartbeats!!.scheduleAtFixedRate(
			{ runCatching { heartbeat.tick(task.id, owner) }.onFailure { log.warn("하트비트 실패(다음 주기에 재시도): {}", it.javaClass.simpleName) } },
			props.heartbeatSeconds,
			props.heartbeatSeconds,
			TimeUnit.SECONDS,
		)
		try {
			val work = load(task.id) ?: return
			when {
				work.equipmentStatus == EquipmentStatus.MAINTENANCE.name ->
					fail(task, owner, FailureKind.EQUIPMENT_MAINTENANCE, "설비가 MAINTENANCE 상태다")

				history.latestApplied(task.equipmentId) == AppliedRecipe(work.partNo, work.version) -> {
					// 멱등 2층(불변 조건 6): 이미 같은 품번·버전이면 브라우저를 열지 않는다
					succeed(task, owner, work.paramsJson, HistoryResult.NO_CHANGE)
					log.info("적용 생략(NO_CHANGE): equipment={}", task.equipmentId)
				}

				else -> {
					browserSlots.acquire()
					try {
						applier.apply(
							ConsoleTarget(task.equipmentId, work.consoleUrl, work.consoleUser, work.consolePassword),
							RecipeToApply(work.partNo, work.version, params(task.id)),
						)
					} finally {
						browserSlots.release()
					}
					succeed(task, owner, work.paramsJson, HistoryResult.APPLIED)
					log.info("적용 완료: equipment={} part_no={} version={}", task.equipmentId, work.partNo, work.version)
				}
			}
		} catch (e: ApplyFailure) {
			fail(task, owner, e.kind, e.message ?: e.kind.name)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
		} catch (e: Exception) {
			// 어댑터·DB의 예상 밖 예외(브라우저 기동 실패 등). 안 잡으면 task가 CLAIMED로 남아 리스 만료 회수만
			// 반복하고 잡이 영원히 RUNNING이다(메타 리뷰 치명 1). 분류는 재시도 O라 상한이 그대로 걸린다.
			log.error("적용 중 예상 밖 예외: {}", e.javaClass.simpleName, e)
			fail(task, owner, FailureKind.SERVER_ERROR, "예상 밖 예외: ${e.javaClass.simpleName}")
		} finally {
			started.stop(meters.timer("rollout_task_duration_seconds"))
			beat.cancel(true)
			MDC.remove("job_id")
			MDC.remove("task_id")
		}
	}

	private fun succeed(task: ClaimedTask, owner: String, paramsJson: String, result: HistoryResult) {
		if (outcomes.succeed(task.id, owner, paramsJson, result)) count("SUCCEEDED")
	}

	private fun fail(task: ClaimedTask, owner: String, kind: FailureKind, message: String) {
		log.warn("적용 실패: equipment={} kind={} message={}", task.equipmentId, kind, message)
		if (outcomes.fail(task.id, owner, "$kind: $message", FailureClassifier.retryable(kind))) count("FAILED")
	}

	/** 한 **시도**의 결과를 센다(재시도로 돌아간 시도도 그 시도로서는 실패다). 노출 이름은 `rollout_task_total{result}`. */
	private fun count(result: String) = meters.counter("rollout_task", "result", result).increment()

	private fun load(taskId: Long): TaskWork? =
		jdbc.sql(
			"""
			select e.status as equipment_status, e.console_url, e.console_user, e.console_password,
			       r.part_no, r.version, r.params::text as params_json
			from rollout_task t
			join rollout_job j on j.id = t.job_id
			join recipe r on r.id = j.recipe_id
			join equipment e on e.id = t.equipment_id
			where t.id = :id
			""".trimIndent(),
		)
			.param("id", taskId)
			.query { rs, _ ->
				TaskWork(
					rs.getString("equipment_status"),
					rs.getString("console_url"),
					rs.getString("console_user"),
					rs.getString("console_password"),
					rs.getString("part_no"),
					rs.getInt("version"),
					rs.getString("params_json"),
				)
			}
			.optional()
			.orElse(null)

	/** 폼 필드명 = params의 키다. 값 변환은 DB에 맡긴다 — 워커에 JSON 파서를 들일 이유가 없다. */
	private fun params(taskId: Long): Map<String, String> =
		jdbc.sql(
			"""
			select kv.key as k, kv.value as v
			from rollout_task t
			join rollout_job j on j.id = t.job_id
			join recipe r on r.id = j.recipe_id
			cross join lateral jsonb_each_text(r.params) kv
			where t.id = :id
			""".trimIndent(),
		)
			.param("id", taskId)
			.query { rs, _ -> rs.getString("k") to rs.getString("v") }
			.list()
			.toMap()

	private companion object {
		const val POLL_MILLIS = 100L
	}
}
