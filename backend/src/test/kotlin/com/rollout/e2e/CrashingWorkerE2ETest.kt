package com.rollout.e2e

import com.rollout.job.WorkerProperties
import com.rollout.worker.ConsoleTarget
import com.rollout.worker.RecipeApplier
import com.rollout.worker.RecipeToApply
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import kotlin.test.assertEquals

/**
 * 치명 1(메타 리뷰 2026-09-17): 어댑터가 `ApplyFailure`가 아닌 예외로 죽으면(브라우저 기동 실패·DB 예외)
 * task가 CLAIMED로 남아 리스 만료 회수만 반복했다. 잡은 영원히 RUNNING이고 사람에게 FAILED로 안 보인다.
 * 여기서 재는 것은 「닫히는가」가 아니라 **fail() 경로로 곧바로 닫히는가**다 — 리스 만료로 닫히면
 * 상한 3회 × 120초라 `awaitJob` 상한을 넘겨 실패한다.
 */
class CrashingWorkerE2ETest @Autowired constructor(
	private val props: WorkerProperties,
) : E2ESupport() {

	@TestConfiguration
	class ExplodingApplier {
		@Bean
		@Primary
		fun exploding(): RecipeApplier = object : RecipeApplier {
			override fun apply(target: ConsoleTarget, recipe: RecipeToApply): Unit =
				throw IllegalStateException("브라우저 기동 실패")
		}
	}

	@Test
	fun unexpectedExceptionFailsTheJobInsteadOfLeavingItRunning() {
		val requester = user()
		val jobId = deploy(recipe(requester), listOf(equipment().first), requester)

		assertEquals("FAILED", awaitJob(jobId), "예상 밖 예외도 상한까지 재시도한 뒤 FAILED로 닫힌다")
		assertEquals(props.maxAttempts, tasks(jobId).single()["attempt"], "상한을 넘겨 재시도하지 않는다")
	}
}
