package com.rollout

import com.rollout.domain.JobStatus
import com.rollout.domain.TaskStatus
import com.rollout.job.JobService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 멱등 1층(설계 §7)과 집계(§5 6번). */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class JobServiceTest @Autowired constructor(
	private val jdbc: JdbcTemplate,
	private val jobs: JobService,
) {

	private val fx = Fixtures(jdbc)

	@Test
	fun sameIdempotencyKeyReturnsExistingJobWithoutNewRows() {
		val user = fx.user()
		val recipe = fx.recipe(user)
		val equipment = listOf(fx.equipment(), fx.equipment())
		val key = fx.idempotencyKey()

		val first = jobs.create(recipe, equipment, user, key)
		// 두 번째 요청이 설비를 더 들고 와도 기존 잡이 그대로다 — 키가 잡을 정한다
		val second = jobs.create(recipe, equipment + fx.equipment(), user, key)

		assertTrue(first.created)
		assertFalse(second.created)
		assertEquals(first.jobId, second.jobId)
		assertEquals(1, jdbc.queryForObject("select count(*)::int from rollout_job where idempotency_key = ?", Int::class.java, key))
		assertEquals(2, jdbc.queryForObject("select count(*)::int from rollout_task where job_id = ?", Int::class.java, first.jobId))
	}

	@Test
	fun aggregateCoversAllTerminalCombinations() {
		assertEquals(JobStatus.RUNNING, JobStatus.aggregate(listOf(TaskStatus.CLAIMED, TaskStatus.SUCCEEDED)))
		assertEquals(JobStatus.RUNNING, JobStatus.aggregate(listOf(TaskStatus.PENDING, TaskStatus.FAILED)))
		assertEquals(JobStatus.SUCCEEDED, JobStatus.aggregate(listOf(TaskStatus.SUCCEEDED, TaskStatus.SUCCEEDED)))
		assertEquals(JobStatus.FAILED, JobStatus.aggregate(listOf(TaskStatus.FAILED, TaskStatus.CANCELLED)))
		assertEquals(JobStatus.PARTIAL, JobStatus.aggregate(listOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED)))
		assertEquals(JobStatus.PARTIAL, JobStatus.aggregate(listOf(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED)))
	}

	@Test
	fun cancelStopsPendingTasksAndRetryPutsThemBack() {
		val user = fx.user()
		val job = jobs.create(fx.recipe(user), listOf(fx.equipment(), fx.equipment()), user, fx.idempotencyKey()).jobId

		assertEquals(2, jobs.cancel(job))
		assertEquals("FAILED", fx.jobStatus(job))

		assertEquals(2, jobs.retry(job))
		assertEquals("RUNNING", fx.jobStatus(job))
		assertEquals(
			2,
			jdbc.queryForObject(
				"select count(*)::int from rollout_task where job_id = ? and status = 'PENDING' and attempt = 0 and next_attempt_at is null",
				Int::class.java,
				job,
			),
		)
	}
}
