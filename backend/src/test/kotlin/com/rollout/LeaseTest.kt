package com.rollout

import com.rollout.job.JobService
import com.rollout.job.LeaseHeartbeat
import com.rollout.job.TaskClaimer
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 리스 회수가 곧 크래시 복구다(설계 §5 7번). 시간을 기다리지 않고 클록을 앞으로 밀어 판정한다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class LeaseTest @Autowired constructor(
	private val jdbc: JdbcTemplate,
	private val jobs: JobService,
	private val claimer: TaskClaimer,
	private val heartbeat: LeaseHeartbeat,
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

	@Test
	fun expiredLeaseIsReclaimedByAnotherWorker() {
		oneTaskJob()
		val a = claimer.claim("A")!!
		val recoveredBefore = meters.counter("lease_expired_recovered").count()

		clock.advance(Duration.ofSeconds(121))
		val b = claimer.claim("B")!!

		assertEquals(a.id, b.id, "만료된 리스의 task를 다른 워커가 회수한다")
		assertEquals("B", jdbc.queryForObject("select claimed_by from rollout_task where id = ?", String::class.java, a.id))
		assertEquals(2, b.attempt, "회수도 한 번의 시도다")
		assertEquals(recoveredBefore + 1, meters.counter("lease_expired_recovered").count())
	}

	@Test
	fun heartbeatKeepsLeaseFromBeingReclaimed() {
		oneTaskJob()
		val a = claimer.claim("A")!!

		clock.advance(Duration.ofSeconds(100))
		assertTrue(heartbeat.tick(a.id, "A"))
		// 갱신이 없었다면 t0+120에 만료돼 B가 받는다
		clock.advance(Duration.ofSeconds(100))

		assertNull(claimer.claim("B"))
	}
}
