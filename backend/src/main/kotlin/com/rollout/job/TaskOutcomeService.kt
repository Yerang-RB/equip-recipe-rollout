package com.rollout.job

import com.rollout.api.AuditWriter
import com.rollout.domain.HistoryResult
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * task 종료 처리(불변 조건 5). 성공은 상태와 이력이 **같은 트랜잭션**이고, 실패는 분류 결과에 따라
 * 백오프 후 복귀하거나 확정 실패다. 두 경로 모두 잡 상태를 다시 집계한다.
 */
@Service
class TaskOutcomeService(
	private val jdbc: JdbcClient,
	private val clock: Clock,
	private val props: WorkerProperties,
	private val jobs: JobService,
	private val notifier: ProgressNotifier,
	private val audit: AuditWriter,
) {

	/**
	 * `workerId`를 함께 보는 이유: 리스가 만료돼 다른 워커가 회수한 task를, 늦게 깨어난 원래 워커가
	 * 성공으로 마감하면 이력이 두 번 쌓인다. 자기 것이 아니면 false를 돌려준다.
	 */
	@Transactional
	fun succeed(taskId: Long, workerId: String, paramsSnapshot: String, result: HistoryResult): Boolean {
		val now = clock.instant().toDb()
		val task = ownedTask(taskId, workerId) ?: return false
		jobs.lockJob(task.jobId)

		val updated = jdbc.sql(
			"""
			update rollout_task
			set status = 'SUCCEEDED', last_error = null, lease_expires_at = null, updated_at = :now
			where id = :id and status = 'CLAIMED' and claimed_by = :worker
			""".trimIndent(),
		)
			.param("now", now)
			.param("id", taskId)
			.param("worker", workerId)
			.update()
		if (updated == 0) return false

		jdbc.sql(
			"""
			insert into application_history(equipment_id, part_no, version, params_snapshot, applied_at, applied_by, result)
			select t.equipment_id, r.part_no, r.version, cast(:params as jsonb), :now, j.requested_by, :result
			from rollout_task t
			join rollout_job j on j.id = t.job_id
			join recipe r on r.id = j.recipe_id
			where t.id = :id
			""".trimIndent(),
		)
			.param("params", paramsSnapshot)
			.param("now", now)
			.param("result", result.name)
			.param("id", taskId)
			.update()

		audit(taskId, task, "OK", mapOf("outcome" to "SUCCEEDED", "history" to result.name))
		jobs.refreshStatus(task.jobId)
		notifier.afterCommit(task.jobId)
		return true
	}

	/**
	 * 워커가 흔적 없이 죽어(JVM 킬·OOM) CLAIMED로 남은 task는 리스 만료로 회수되는데, 상한을 소진하면
	 * 회수 대상에서도 빠져 아무도 닫지 않는다 — 잡이 영원히 RUNNING이다(메타 리뷰 치명 1). 여기서 닫는다.
	 */
	@Transactional
	fun reapExhausted(): Int {
		val now = clock.instant()
		val ids = jdbc.sql(
			"""
			select id from rollout_task
			where status = 'CLAIMED' and lease_expires_at < :now and attempt >= :max
			order by id
			for update skip locked
			""".trimIndent(),
		)
			.param("now", now.toDb())
			.param("max", props.maxAttempts)
			.query { rs, _ -> rs.getLong("id") }
			.list()

		for (id in ids) {
			val task = taskRow(id) ?: continue
			jobs.lockJob(task.jobId)
			jdbc.sql(
				"""
				update rollout_task
				set status = 'FAILED', last_error = coalesce(last_error, :error), claimed_by = null,
				    lease_expires_at = null, next_attempt_at = null, updated_at = :now
				where id = :id and status = 'CLAIMED'
				""".trimIndent(),
			)
				.param("error", "워커 응답 없음 — 재시도 상한 소진")
				.param("now", now.toDb())
				.param("id", id)
				.update()
			audit(id, task, "ERROR", mapOf("outcome" to "FAILED", "reason" to "LEASE_EXHAUSTED"))
			jobs.refreshStatus(task.jobId)
			notifier.afterCommit(task.jobId)
		}
		return ids.size
	}

	/**
	 * 잡이 취소된 뒤 끝난 시도는 재시도로 돌아가지 않고 `CANCELLED`로 닫힌다(리뷰 B2) — 그대로 두면 취소된 잡의
	 * 레시피가 백오프 뒤 다시 집혀 설비에 쓰인다. 확정 실패(재시도 X 분류·상한 소진)는 취소와 무관하게 `FAILED`다.
	 */
	@Transactional
	fun fail(taskId: Long, workerId: String, error: String, retryable: Boolean): Boolean {
		val now = clock.instant()
		val task = ownedTask(taskId, workerId) ?: return false
		jobs.lockJob(task.jobId)

		// attempt는 클레임 때 이미 올라갔다 — 여기의 attempt가 「지금까지 한 시도 횟수」다
		val outcome = when {
			!retryable || task.attempt >= props.maxAttempts -> "FAILED"
			task.lastError == JobService.CANCEL_NOTE -> "CANCELLED"
			else -> "PENDING"
		}
		if (outcome == "PENDING") {
			val backoff = Backoff.of(
				task.attempt,
				Duration.ofSeconds(props.backoffBaseSeconds),
				Duration.ofSeconds(props.backoffMaxSeconds),
			)
			jdbc.sql(
				"""
				update rollout_task
				set status = 'PENDING', last_error = :error, claimed_by = null, lease_expires_at = null,
				    next_attempt_at = :next, updated_at = :now
				where id = :id and status = 'CLAIMED' and claimed_by = :worker
				""".trimIndent(),
			)
				.param("error", error)
				.param("next", now.plus(backoff).toDb())
				.param("now", now.toDb())
				.param("id", taskId)
				.param("worker", workerId)
				.update()
		} else {
			jdbc.sql(
				"""
				update rollout_task
				set status = :status, last_error = :error, lease_expires_at = null, next_attempt_at = null, updated_at = :now
				where id = :id and status = 'CLAIMED' and claimed_by = :worker
				""".trimIndent(),
			)
				.param("status", outcome)
				.param("error", error)
				.param("now", now.toDb())
				.param("id", taskId)
				.param("worker", workerId)
				.update()
		}

		// 오류 문구는 콘솔이 만든 텍스트라 감사에 싣지 않는다 — 결과 전이만 남긴다(불변 조건 9③).
		audit(taskId, task, "ERROR", mapOf("outcome" to outcome))
		jobs.refreshStatus(task.jobId)
		notifier.afterCommit(task.jobId)
		return true
	}

	private data class OwnedTask(val jobId: Long, val equipmentId: Long, val requestedBy: Long, val attempt: Int, val lastError: String?)

	/** 워커 대행 감사 행(설계 §8 — `actor=system, on_behalf_of=<요청자>`). */
	private fun audit(taskId: Long, task: OwnedTask, result: String, detail: Map<String, Any?>) =
		audit.writeSystem(
			"WORKER apply",
			"rollout_task",
			taskId,
			result,
			task.requestedBy,
			detail + mapOf("job_id" to task.jobId, "equipment_id" to task.equipmentId),
		)

	private fun ownedTask(taskId: Long, workerId: String): OwnedTask? =
		jdbc.sql(
			"""
			select t.job_id, t.equipment_id, j.requested_by, t.attempt, t.last_error
			from rollout_task t join rollout_job j on j.id = t.job_id
			where t.id = :id and t.status = 'CLAIMED' and t.claimed_by = :worker
			""".trimIndent(),
		)
			.param("id", taskId)
			.param("worker", workerId)
			.query { rs, _ ->
				OwnedTask(rs.getLong("job_id"), rs.getLong("equipment_id"), rs.getLong("requested_by"), rs.getInt("attempt"), rs.getString("last_error"))
			}
			.optional()
			.orElse(null)

	private fun taskRow(taskId: Long): OwnedTask? =
		jdbc.sql(
			"""
			select t.job_id, t.equipment_id, j.requested_by, t.attempt, t.last_error
			from rollout_task t join rollout_job j on j.id = t.job_id
			where t.id = :id and t.status = 'CLAIMED'
			""".trimIndent(),
		)
			.param("id", taskId)
			.query { rs, _ ->
				OwnedTask(rs.getLong("job_id"), rs.getLong("equipment_id"), rs.getLong("requested_by"), rs.getInt("attempt"), rs.getString("last_error"))
			}
			.optional()
			.orElse(null)
}
