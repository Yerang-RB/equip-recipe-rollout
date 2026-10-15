package com.rollout

import com.rollout.job.HistoryRepository
import com.rollout.job.JobService
import com.rollout.job.LeaseHeartbeat
import com.rollout.job.TaskClaimer
import com.rollout.job.TaskOutcomeService
import com.rollout.job.WorkerProperties
import com.rollout.worker.ClaimLoop
import com.rollout.worker.ConsoleTarget
import com.rollout.worker.RecipeApplier
import com.rollout.worker.RecipeToApply
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * 주의 2(메타 리뷰 2026-09-17): `scheduleAtFixedRate`는 작업이 예외를 던지면 **이후 실행을 전부 취소**한다.
 * 일시적 DB 오류 한 번으로 그 task의 리스 갱신이 영구히 멈추고, 작업이 리스보다 길어지면 다른 워커가
 * 회수해 **같은 설비에 두 번 쓴다**. 하트비트는 실패해도 다음 주기에 다시 시도해야 한다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class HeartbeatResilienceTest @Autowired constructor(
	private val jdbc: JdbcTemplate,
	private val jdbcClient: JdbcClient,
	private val jobs: JobService,
	private val claimer: TaskClaimer,
	private val outcomes: TaskOutcomeService,
	private val heartbeat: LeaseHeartbeat,
	private val history: HistoryRepository,
	private val props: WorkerProperties,
	private val clock: MutableClock,
	private val meters: MeterRegistry,
) {

	private val fx = Fixtures(jdbc)

	@BeforeEach
	fun closeOpenTasks() = fx.closeOpenTasks()

	/** 첫 호출만 터지고 그 뒤로는 정상인 하트비트 — 일시적 DB 오류의 모양이다. */
	private class FlakyHeartbeat(
		private val real: LeaseHeartbeat,
		val calls: AtomicInteger,
		jdbc: JdbcClient,
		clock: java.time.Clock,
		props: WorkerProperties,
	) : LeaseHeartbeat(jdbc, clock, props) {
		override fun tick(taskId: Long, workerId: String): Boolean {
			if (calls.incrementAndGet() == 1) throw IllegalStateException("커넥션 풀 고갈")
			return real.tick(taskId, workerId)
		}
	}

	@Test
	fun tickFailureDoesNotStopLaterHeartbeats() {
		val calls = AtomicInteger()
		val slow = object : RecipeApplier {
			override fun apply(target: ConsoleTarget, recipe: RecipeToApply) = Thread.sleep(3_500)
		}
		val loop = ClaimLoop(
			claimer, outcomes, FlakyHeartbeat(heartbeat, calls, jdbcClient, clock, props), history, slow, jdbcClient,
			props.copy(heartbeatSeconds = 1), clock, meters,
		)
		loop.start()
		try {
			// 루프가 돌기 시작한 뒤에 잡을 만든다 — 집는 것도 하트비트도 실제 워커 경로 그대로다
			val user = fx.user()
			jobs.create(fx.recipe(user), listOf(fx.equipment()), user, fx.idempotencyKey())
			val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
			while (System.nanoTime() < deadline && calls.get() < 2) Thread.sleep(50)
		} finally {
			loop.stop()
		}

		assertTrue(calls.get() >= 2, "첫 tick이 터져도 다음 주기에 다시 뛴다(실제 호출 ${calls.get()}회)")
	}
}
