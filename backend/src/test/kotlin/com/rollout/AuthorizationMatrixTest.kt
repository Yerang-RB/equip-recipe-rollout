package com.rollout

import com.rollout.api.Action
import com.rollout.domain.Role
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 설계 §8 매트릭스를 **실호출로** 전수 확인한다(3역할 × 7행위 = 21칸). 칸마다 대표 엔드포인트를 실제로
 * 밟아 허용은 2xx, 거부는 403 `FORBIDDEN`이어야 한다 — `Policy`를 직접 부르는 단위 테스트는 컨트롤러가
 * 검사를 빼먹은 경우를 못 잡는다.
 */
class AuthorizationMatrixTest : ApiTestSupport() {

	@ParameterizedTest(name = "{0} × {1}")
	@MethodSource("cells")
	fun everyRoleActionCellMatchesTheMatrix(role: Role, action: Action) {
		assertEquals(21, cells().size, "3역할 × 7행위")
		val op = representative(action)
		assertEquals(action, ExpectedMatrix.actionOf[op], "대표 엔드포인트가 그 칸의 것인가")
		assertTrue(op in Contract.operations, "대표 엔드포인트가 계약에 있다: $op")

		val response = perform(op, newUser(role))
		if (role in ExpectedMatrix.allowed.getValue(action)) {
			assertTrue(response.status in 200..299, "$role × $action 은 허용: $op → ${response.status} ${response.contentAsString}")
		} else {
			assertEquals(403, response.status, "$role × $action 은 거부: $op")
			assertEquals("FORBIDDEN", codeOf(response))
		}
	}

	/** 경계 1 — 4-eyes: APPROVER라도 자기가 만든 초안은 자기가 승인하지 못한다. */
	@Test
	fun approverCannotApproveOwnRecipe() {
		val author = newUser(Role.APPROVER)
		val recipeId = newRecipe(author.id, "DRAFT")

		val own = mockMvc.perform(post("/api/recipes/{id}/approve", recipeId).session(session(author.id))).andReturn().response
		assertEquals(403, own.status)
		assertEquals("SELF_APPROVAL_FORBIDDEN", codeOf(own))
		assertEquals("DRAFT", recipeStatus(recipeId), "거부된 승인은 상태를 바꾸지 않는다")

		val other = newUser(Role.APPROVER)
		val allowed = mockMvc.perform(post("/api/recipes/{id}/approve", recipeId).session(session(other.id))).andReturn().response
		assertEquals(200, allowed.status)
		assertEquals("ACTIVE", recipeStatus(recipeId))
	}

	/**
	 * 경계 1' — 남의 초안을 고친 사람이 새 작성자다(리뷰 B1). 덮어쓴 내용을 스스로 승인하면 4-eyes가 무력해진다 —
	 * 수정 전에는 PUT 200 → 승인 200이었다.
	 */
	@Test
	fun editorOfOthersDraftCannotApproveIt() {
		val author = newUser(Role.OPERATOR)
		val editor = newUser(Role.APPROVER)
		val recipeId = newRecipe(author.id, "DRAFT")

		val edited = mockMvc.perform(put("/api/recipes/{id}", recipeId).session(session(editor.id)).jsonBody(recipeJson())).andReturn().response
		assertEquals(200, edited.status)

		val self = mockMvc.perform(post("/api/recipes/{id}/approve", recipeId).session(session(editor.id))).andReturn().response
		assertEquals(403, self.status)
		assertEquals("SELF_APPROVAL_FORBIDDEN", codeOf(self))
		assertEquals("DRAFT", recipeStatus(recipeId))

		val other = mockMvc.perform(post("/api/recipes/{id}/approve", recipeId).session(session(newUser(Role.APPROVER).id))).andReturn().response
		assertEquals(200, other.status, "고친 사람이 아닌 APPROVER는 승인한다")
	}

	/** 경계 2 — OPERATOR는 자기 잡만 취소한다(APPROVER·ADMIN은 전부). */
	@Test
	fun operatorCancelsOnlyOwnJob() {
		val owner = newUser(Role.OPERATOR)
		val stranger = newUser(Role.OPERATOR)
		val jobId = newJob(owner.id)

		val foreign = mockMvc.perform(post("/api/jobs/{id}/cancel", jobId).session(session(stranger.id))).andReturn().response
		assertEquals(403, foreign.status)
		assertEquals("FORBIDDEN", codeOf(foreign))
		assertEquals("PENDING", jdbc.queryForObject("select status from rollout_task where job_id = ?", String::class.java, jobId))

		val own = mockMvc.perform(post("/api/jobs/{id}/cancel", jobId).session(session(owner.id))).andReturn().response
		assertEquals(200, own.status)
		assertEquals("CANCELLED", jdbc.queryForObject("select status from rollout_task where job_id = ?", String::class.java, jobId))
	}

	/** 계약의 (경로, 메서드) 전부가 21칸 중 하나이거나 무인증 예외 넷 중 하나다 — 등호로 본다. */
	@Test
	fun everyContractEndpointHasAMatrixRow() {
		assertEquals(
			Contract.operations.filter { !it.public }.toSet(),
			ExpectedMatrix.actionOf.keys,
			"계약의 인증 오퍼레이션 집합 == 매트릭스 행이 붙은 집합",
		)
		assertEquals(Action.entries.toSet(), ExpectedMatrix.actionOf.values.toSet(), "7행위 전부가 실제 엔드포인트를 가진다")
		assertEquals(
			setOf("POST /api/auth/login", "GET /api/health", "/actuator/health", "/actuator/prometheus"),
			Contract.operations.filter { it.public }.map { it.toString() }.toSet() + setOf("/actuator/health", "/actuator/prometheus"),
			"무인증 허용은 정확히 넷",
		)
	}

	private fun recipeStatus(id: Long) = jdbc.queryForObject("select status from recipe where id = ?", String::class.java, id)

	private fun representative(action: Action): Contract.Op = when (action) {
		Action.VIEW -> op("GET", "/api/equipment")
		Action.AUDIT_VIEW -> op("GET", "/api/audit")
		Action.RECIPE_DRAFT -> op("POST", "/api/recipes")
		Action.RECIPE_APPROVE_RETIRE -> op("POST", "/api/recipes/{id}/approve")
		Action.JOB_CREATE -> op("POST", "/api/jobs")
		Action.JOB_CANCEL_RETRY -> op("POST", "/api/jobs/{id}/cancel")
		Action.USER_EQUIPMENT_MANAGE -> op("POST", "/api/equipment")
	}

	private fun op(method: String, path: String) = Contract.Op(method, path, public = false)

	companion object {
		@JvmStatic
		fun cells(): List<Arguments> = Role.entries.flatMap { role -> Action.entries.map { action -> Arguments.of(role, action) } }
	}
}
