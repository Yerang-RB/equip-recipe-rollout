package com.rollout

import com.rollout.domain.Role
import com.rollout.job.JobService
import com.rollout.job.TaskClaimer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 주의 5-①·주의 4(메타 리뷰 2026-09-17): 사람이 「멈춰라」·「쓰지 마라」라고 한 것이 설비에 써지는 두 경로.
 */
class CancelAndRetryGuardTest : ApiTestSupport() {

	@Autowired private lateinit var jobs: JobService

	@Autowired private lateinit var claimer: TaskClaimer

	@Autowired private lateinit var clock: MutableClock

	@Test
	fun cancelClosesTasksWhoseWorkerIsGone() {
		val jobId = newJob(newUser(Role.OPERATOR).id)
		claimer.claim("죽을워커")!!
		clock.advance(Duration.ofSeconds(121)) // 하트비트가 끊겨 리스가 만료됐다 = 실행 중이 아니다

		jobs.cancel(jobId)

		assertEquals(
			"CANCELLED",
			jdbc.queryForObject("select status from rollout_task where job_id = ?", String::class.java, jobId),
			"실행 중이 아닌 CLAIMED는 취소로 닫힌다 — 남겨 두면 다른 워커가 회수해 설비에 쓴다",
		)
	}

	@Test
	fun retryIsRejectedWhenRecipeIsNoLongerActive() {
		val operator = newUser(Role.OPERATOR)
		val jobId = newJob(operator.id)
		jobs.cancel(jobId)
		jdbc.update("update recipe set status = 'RETIRED' where id = (select recipe_id from rollout_job where id = ?)", jobId)

		val response = mockMvc.perform(post("/api/jobs/{id}/retry", jobId).session(session(operator.id))).andReturn().response

		assertEquals(409, response.status, "폐기된 레시피로는 재시도할 수 없다(생성과 같은 규칙)")
		assertEquals("RECIPE_NOT_ACTIVE", codeOf(response))
	}

	/**
	 * 발견 2(2026-09-17 데모 실사용): 모든 task가 끝난 뒤 누른 취소가 200으로 통과해 감사에 OK로 남았다 —
	 * 「취소했다」는 기록만 남고 실제로는 아무 일도 없었다(실사용 2건, 각각 12초·9초 늦음).
	 */
	@Test
	fun cancelIsRejectedWhenTheJobIsAlreadyFinished() {
		val operator = newUser(Role.OPERATOR)
		val jobId = newJob(operator.id)
		jdbc.update("update rollout_task set status = 'SUCCEEDED', updated_at = now() where job_id = ?", jobId)
		jdbc.update("update rollout_job set status = 'SUCCEEDED', updated_at = now() where id = ?", jobId)

		val response = mockMvc.perform(post("/api/jobs/{id}/cancel", jobId).session(session(operator.id))).andReturn().response

		assertEquals(409, response.status, "끝난 잡은 취소할 것이 없다")
		assertEquals("JOB_ALREADY_FINISHED", codeOf(response))
	}
}
