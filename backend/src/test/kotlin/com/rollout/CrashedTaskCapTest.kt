package com.rollout

import com.rollout.job.JobService
import com.rollout.job.TaskClaimer
import com.rollout.job.TaskOutcomeService
import com.rollout.job.WorkerProperties
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 치명 1(메타 리뷰 2026-09-17): 워커가 `ApplyFailure`가 아닌 예외로 죽으면 task가 CLAIMED로 남고,
 * 리스 만료 회수에는 상한이 없어 무한 재클레임이 된다 — 설계 §5 5번(상한 3회 초과면 FAILED) 위반.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class CrashedTaskCapTest @Autowired constructor(
	private val jdbc: JdbcTemplate,
	private val jobs: JobService,
	private val claimer: TaskClaimer,
	private val outcomes: TaskOutcomeService,
	private val props: WorkerProperties,
	private val clock: MutableClock,
	private val meters: MeterRegistry,
) {

	private val fx = Fixtures(jdbc)

	@BeforeEach
	fun closeOpenTasks() = fx.closeOpenTasks()

	private fun oneTaskJob(): Long {
		val user = fx.user()
		return jobs.create(fx.recipe(user), listOf(fx.equipment()), user, fx.idempotencyKey()).jobId
	}

	private fun status(jobId: Long): String =
		jdbc.queryForObject("select status from rollout_task where job_id = ?", String::class.java, jobId)!!

	@Test
	fun crashedTaskStopsBeingReclaimedAtMaxAttempts() {
		val jobId = oneTaskJob()

		// 워커가 매번 흔적 없이 죽는다: 클레임만 되고 하트비트도 fail()도 없다
		var claims = 0
		repeat(props.maxAttempts + 3) {
			if (claimer.claim("W$it") != null) claims++
			clock.advance(Duration.ofSeconds(121))
		}

		assertEquals(props.maxAttempts, claims, "상한을 넘긴 만료 리스는 다시 집지 않는다")

		outcomes.reapExhausted()
		assertEquals("FAILED", status(jobId), "상한을 소진한 task는 FAILED로 닫힌다")
	}
}
