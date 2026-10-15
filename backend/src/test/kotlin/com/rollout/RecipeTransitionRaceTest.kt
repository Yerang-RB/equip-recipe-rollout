package com.rollout

import com.rollout.domain.Role
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * 주의 1(메타 리뷰 2026-09-17): 레시피 전이가 「읽고 검사 → 조건 없는 UPDATE」라 두 요청이 겹치면
 * 둘 다 통과한다. 4-eyes(불변 조건 3)가 경합으로 다시 열리는 자리라 인가 매트릭스만으로는 못 잡는다.
 */
class RecipeTransitionRaceTest : ApiTestSupport() {

	@Test
	fun concurrentApprovalsLetExactlyOneWin() {
		val author = newUser(Role.OPERATOR)
		val a = newUser(Role.APPROVER)
		val b = newUser(Role.APPROVER)

		repeat(10) {
			val recipe = newRecipe(author.id, "DRAFT")
			val barrier = CyclicBarrier(2)
			val pool = Executors.newFixedThreadPool(2)
			val codes = listOf(a, b).map { approver ->
				pool.submit<Int> {
					barrier.await(5, TimeUnit.SECONDS)
					mockMvc.perform(post("/api/recipes/{id}/approve", recipe).session(session(approver.id)))
						.andReturn().response.status
				}
			}.map { it.get(10, TimeUnit.SECONDS) }
			pool.shutdown()

			assertEquals(1, codes.count { it == 200 }, "승인은 한 번만 성공한다: $codes")
			assertEquals(1, codes.count { it == 409 }, "진 쪽은 전이 충돌이다: $codes")
		}
	}
}
