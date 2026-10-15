package com.rollout

import com.rollout.domain.HistoryResult
import com.rollout.job.JobService
import com.rollout.job.LeaseHeartbeat
import com.rollout.job.TaskClaimer
import com.rollout.job.TaskOutcomeService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 성공은 상태와 이력이 같은 트랜잭션이고(설계 §5 4번), 실패는 분류와 시도 횟수가 다음 행동을 정한다(5번).
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class TaskOutcomeTest @Autowired constructor(
	private val jdbc: JdbcTemplate,
	private val jobs: JobService,
	private val claimer: TaskClaimer,
	private val outcomes: TaskOutcomeService,
	private val clock: MutableClock,
	private val heartbeat: LeaseHeartbeat,
) {

	private val fx = Fixtures(jdbc)

	@BeforeEach
	fun closeOpenTasks() = fx.closeOpenTasks()

	private fun oneTaskJob(): Pair<Long, Long> {
		val user = fx.user()
		val equipment = fx.equipment()
		val job = jobs.create(fx.recipe(user), listOf(equipment), user, fx.idempotencyKey()).jobId
		return job to equipment
	}

	private fun historyCount(equipmentId: Long): Int =
		jdbc.queryForObject("select count(*)::int from application_history where equipment_id = ?", Int::class.java, equipmentId)!!

	private fun nextAttemptAt(taskId: Long) =
		jdbc.queryForObject("select next_attempt_at from rollout_task where id = ?", OffsetDateTime::class.java, taskId)?.toInstant()

	@Test
	fun successWritesHistoryAndStatusAtomically() {
		val (job, equipment) = oneTaskJob()
		val task = claimer.claim("A")!!

		// 프로브: 이력 INSERT만 깨뜨린다(jsonb로 캐스팅되지 않는 스냅샷). 상태 갱신이 먼저 실행된 뒤다.
		assertFailsWith<DataAccessException> { outcomes.succeed(task.id, "A", "not-json", HistoryResult.APPLIED) }
		assertEquals("CLAIMED", fx.taskStatus(task.id), "이력이 실패하면 상태도 남지 않는다")
		assertEquals(0, historyCount(equipment))

		assertTrue(outcomes.succeed(task.id, "A", """{"temp":200}""", HistoryResult.APPLIED))
		assertEquals("SUCCEEDED", fx.taskStatus(task.id))
		assertEquals(1, historyCount(equipment))
		assertEquals("SUCCEEDED", fx.jobStatus(job))
	}

	@Test
	fun retryableFailureSchedulesBackoffAndThirdFailureIsFinal() {
		val (job, _) = oneTaskJob()

		val first = claimer.claim("A")!!
		assertEquals(1, first.attempt)
		assertTrue(outcomes.fail(first.id, "A", "타임아웃", retryable = true))
		assertEquals("PENDING", fx.taskStatus(first.id))
		assertEquals(clock.instant().plusSeconds(2), nextAttemptAt(first.id), "backoff(1) = 2초")
		assertNull(claimer.claim("B"), "백오프 시각 전에는 집히지 않는다")

		clock.advance(Duration.ofSeconds(2))
		val second = claimer.claim("B")!!
		assertEquals(2, second.attempt)
		assertTrue(outcomes.fail(second.id, "B", "5xx", retryable = true))
		assertEquals(clock.instant().plusSeconds(4), nextAttemptAt(second.id), "backoff(2) = 4초")

		clock.advance(Duration.ofSeconds(4))
		val third = claimer.claim("C")!!
		assertEquals(3, third.attempt)
		assertTrue(outcomes.fail(third.id, "C", "네트워크", retryable = true))

		assertEquals("FAILED", fx.taskStatus(third.id), "총 3회 시도가 상한이다")
		assertEquals("FAILED", fx.jobStatus(job))
		assertNull(claimer.claim("D"))
	}

	@Test
	fun nonRetryableFailureIsFinalAtAttemptOne() {
		val (job, _) = oneTaskJob()
		val task = claimer.claim("A")!!

		assertTrue(outcomes.fail(task.id, "A", "VERIFY_MISMATCH", retryable = false))

		assertEquals("FAILED", fx.taskStatus(task.id))
		assertEquals(1, jdbc.queryForObject("select attempt from rollout_task where id = ?", Int::class.java, task.id))
		assertNull(nextAttemptAt(task.id))
		assertEquals("FAILED", fx.jobStatus(job))
	}

	/** 리뷰 B2 — 취소 뒤 끝난 실행 중 시도는 재시도로 돌아가지 않는다(수정 전: PENDING으로 돌아가 다시 집혔다). */
	@Test
	fun claimedTaskOfCancelledJobIsNotRetried() {
		val (job, _) = oneTaskJob()
		val task = claimer.claim("A")!!

		assertEquals(0, jobs.cancel(job), "실행 중인 task는 취소로 바뀌지 않는다")
		assertEquals("CLAIMED", fx.taskStatus(task.id))

		assertTrue(outcomes.fail(task.id, "A", "타임아웃", retryable = true))
		assertEquals("CANCELLED", fx.taskStatus(task.id))
		clock.advance(Duration.ofMinutes(5))
		assertNull(claimer.claim("B"), "취소된 잡의 task는 다시 집히지 않는다")

		// 재시도는 사람이 명시적으로 한다 — 그때는 다시 돈다
		assertEquals(1, jobs.retry(job))
		assertEquals(task.id, claimer.claim("B")!!.id)
	}

	/**
	 * 리뷰 B6·M3·M4 — 리스를 뺏긴 소유자는 끝내지도 연장하지도 못한다. `claimed_by` 조건을 지우면 이 테스트가
	 * 붉어진다(수정 전에는 어느 테스트도 이 조건을 밟지 않았다).
	 */
	@Test
	fun staleOwnerCannotFinishOrExtendReclaimedTask() {
		val (_, equipment) = oneTaskJob()
		val a = claimer.claim("A")!!
		clock.advance(Duration.ofSeconds(121))
		val b = claimer.claim("B")!!
		assertEquals(a.id, b.id)

		assertEquals(false, heartbeat.tick(a.id, "A"), "뺏긴 리스를 되살리지 못한다")
		assertEquals(false, outcomes.succeed(a.id, "A", """{"temp":200}""", HistoryResult.APPLIED))
		assertEquals(false, outcomes.fail(a.id, "A", "타임아웃", retryable = false))
		assertEquals("CLAIMED", fx.taskStatus(a.id))
		assertEquals(0, historyCount(equipment))

		assertTrue(outcomes.succeed(b.id, "B", """{"temp":200}""", HistoryResult.APPLIED))
		assertEquals(1, historyCount(equipment))
	}

	/** 리뷰 B3 — 워커 대행 기록은 `actor=system` + `on_behalf_of=<요청자>`로 감사에 남는다(설계 §8). */
	@Test
	fun workerOutcomesWriteSystemAuditRows() {
		val requester = fx.user()
		val job = jobs.create(fx.recipe(requester), listOf(fx.equipment(), fx.equipment()), requester, fx.idempotencyKey()).jobId
		val baseline = jdbc.queryForObject("select coalesce(max(id), 0) from audit_log", Long::class.java)!!

		val first = claimer.claim("A")!!
		assertTrue(outcomes.succeed(first.id, "A", """{"temp":200}""", HistoryResult.APPLIED))
		val second = claimer.claim("A")!!
		assertTrue(outcomes.fail(second.id, "A", "VALUE_REJECTED: 콘솔 비밀 문구", retryable = false))

		val rows = jdbc.queryForList(
			"select actor_id, actor_name, action, target_id, result, detail->>'on_behalf_of' as on_behalf_of, detail->>'outcome' as outcome, cast(detail as text) as detail from audit_log where id > ? and target_type = 'rollout_task' order by id",
			baseline,
		)
		assertEquals(listOf(first.id, second.id), rows.map { (it["target_id"] as Number).toLong() })
		assertEquals(listOf("OK", "ERROR"), rows.map { it["result"] })
		assertEquals(listOf("SUCCEEDED", "FAILED"), rows.map { it["outcome"] })
		rows.forEach {
			assertNull(it["actor_id"])
			assertEquals("system", it["actor_name"])
			assertEquals("WORKER apply", it["action"])
			assertEquals(requester.toString(), it["on_behalf_of"])
			assertTrue("콘솔 비밀 문구" !in (it["detail"] as String), "콘솔 오류 문구는 감사에 싣지 않는다")
		}
		assertEquals("PARTIAL", fx.jobStatus(job))
	}

	/**
	 * 발견 1(2026-09-17 데모 실사용): 재시도로 성공했는데 직전 시도의 오류 문구가 남아 화면 오류 열에
	 * 그대로 뜬다(실사용에서 4건). 성공은 성공만 보여야 한다.
	 */
	@Test
	fun successClearsTheErrorOfTheFailedAttempt() {
		val user = fx.user()
		val jobId = jobs.create(fx.recipe(user), listOf(fx.equipment()), user, fx.idempotencyKey()).jobId
		val first = claimer.claim("W1")!!
		outcomes.fail(first.id, "W1", "SERVER_ERROR: 콘솔 저장 응답 500", true)
		clock.advance(java.time.Duration.ofSeconds(121))
		val second = claimer.claim("W2")!!
		outcomes.succeed(second.id, "W2", "{}", com.rollout.domain.HistoryResult.APPLIED)

		assertNull(
			jdbc.queryForObject("select last_error from rollout_task where job_id = ?", String::class.java, jobId),
			"성공한 task에 직전 실패의 오류가 남지 않는다",
		)
	}
}
