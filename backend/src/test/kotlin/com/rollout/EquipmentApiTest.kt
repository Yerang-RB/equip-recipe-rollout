package com.rollout

import com.rollout.domain.Role
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 설비 콘솔 자격증명은 우리 사용자 계정과 다른 비밀값이고, 응답 어디에도 나오지 않는다(설계 §8).
 * 지키는 것은 프레임워크가 아니라 **열을 하나씩 적은 select**다 — `select *`로 바꾸면 이 테스트가 붉어진다.
 */
class EquipmentApiTest : ApiTestSupport() {

	@Test
	fun consoleCredentialsNeverAppearInResponses() {
		val admin = newUser(Role.ADMIN)
		val consolePassword = "콘솔비밀-${UUID.randomUUID()}"

		val created = mockMvc.perform(
			post("/api/equipment").session(session(admin.id)).jsonBody(equipmentJson(consolePassword)),
		).andReturn().response
		assertEquals(201, created.status)
		val id = (bodyOf(created)["id"] as Number).toLong()
		val code = bodyOf(created)["code"] as String

		val updated = mockMvc.perform(
			put("/api/equipment/{id}", id).session(session(admin.id))
				.jsonBody("""{"consoleUrl":"http://127.0.0.1:1/","model":"M2","status":"MAINTENANCE","consoleUser":"cu2","consolePassword":"$consolePassword"}"""),
		).andReturn().response
		assertEquals(200, updated.status)

		val list = mockMvc.perform(get("/api/equipment").session(session(admin.id))).andReturn().response
		assertEquals(200, list.status)

		// 양의 앵커: 같은 응답에 설비가 실제로 들어 있다(빈 목록으로 통과하지 않는다)
		assertTrue(code in list.contentAsString, "목록에 방금 만든 설비가 있다")
		assertEquals("MAINTENANCE", bodyOf(updated)["status"])

		listOf(created, updated, list).forEach {
			assertFalse(consolePassword in it.contentAsString, "콘솔 비밀번호가 응답에 없다: ${it.contentAsString}")
			assertFalse("consolePassword" in it.contentAsString, "콘솔 비밀번호 필드 자체가 없다")
			assertFalse("consoleUser" in it.contentAsString, "콘솔 계정 필드도 없다(불변 조건 16 · 리뷰 AR4)")
		}
		// 저장은 됐다 — 응답에서만 빠진 것이지 값을 버린 게 아니다
		assertEquals(
			consolePassword,
			jdbc.queryForObject("select console_password from equipment where id = ?", String::class.java, id),
		)
	}

	/**
	 * 리뷰 B5·B8·B9·AR6 — 계약의 길이·형식 제약은 서버가 400 `INVALID_REQUEST`로 강제한다. 수정 전: 빈 사용자명
	 * 201 · 101자 콘솔 계정 409 · URL `"x"` 수용 · 비밀번호 없이 URL만 바꿔 저장된 비밀번호를 다른 호스트로 보냄.
	 */
	@Test
	fun contractConstraintsAreEnforcedAsInvalidRequest() {
		val admin = newUser(Role.ADMIN)
		val operator = newUser(Role.OPERATOR)
		val id = newEquipment()

		val rejected = listOf(
			post("/api/users").session(session(admin.id))
				.jsonBody("""{"username":"","displayName":"","role":"OPERATOR","password":""}"""),
			post("/api/equipment").session(session(admin.id))
				.jsonBody(equipmentJson().replace("\"consoleUser\":\"cu\"", "\"consoleUser\":\"${"u".repeat(101)}\"")),
			post("/api/equipment").session(session(admin.id))
				.jsonBody(equipmentJson().replace("http://127.0.0.1:1/", "x")),
			put("/api/equipment/{id}", id).session(session(admin.id))
				.jsonBody("""{"consoleUrl":"http://evil.example/","model":"M1","status":"RUNNING","consoleUser":"cu"}"""),
			post("/api/recipes").session(session(operator.id))
				.jsonBody("""{"partNo":"P-${UUID.randomUUID()}","version":1,"params":{"a] , input[name=x":"1"}}"""),
			post("/api/recipes").session(session(operator.id))
				.jsonBody("""{"partNo":"P-${UUID.randomUUID()}","version":0,"params":{}}"""),
		)
		rejected.forEach { request ->
			val response = mockMvc.perform(request).andReturn().response
			assertEquals(400, response.status, response.contentAsString)
			assertEquals("INVALID_REQUEST", codeOf(response))
		}
		assertEquals("http://127.0.0.1:1/", jdbc.queryForObject("select console_url from equipment where id = ?", String::class.java, id))

		// 양의 앵커: 비밀번호와 함께면 URL을 바꿀 수 있다
		val moved = mockMvc.perform(
			put("/api/equipment/{id}", id).session(session(admin.id))
				.jsonBody("""{"consoleUrl":"http://127.0.0.1:2/","model":"M1","status":"RUNNING","consoleUser":"cu","consolePassword":"new"}"""),
		).andReturn().response
		assertEquals(200, moved.status, moved.contentAsString)
	}

	/** 결정 대기 2ⓖ — MAINTENANCE 설비는 잡 요청에 넣을 수 없다(400). 작업 9에서 API에 빠져 있던 것을 발견해 넣었다. */
	@Test
	fun jobCannotTargetMaintenanceEquipment() {
		val operator = newUser(Role.OPERATOR)
		val recipe = newRecipe(newUser(Role.APPROVER).id, "ACTIVE")
		val running = newEquipment()
		val maintenance = newEquipment()
		jdbc.update("update equipment set status = 'MAINTENANCE' where id = ?", maintenance)

		val rejected = mockMvc.perform(
			post("/api/jobs").session(session(operator.id)).header("Idempotency-Key", "k-${UUID.randomUUID()}")
				.jsonBody("""{"recipeId":$recipe,"equipmentIds":[$running,$maintenance]}"""),
		).andReturn().response
		assertEquals(400, rejected.status, rejected.contentAsString)
		assertEquals("INVALID_REQUEST", codeOf(rejected))
		assertEquals(0, jdbc.queryForObject("select count(*)::int from rollout_task where equipment_id = ?", Int::class.java, running))

		val accepted = mockMvc.perform(
			post("/api/jobs").session(session(operator.id)).header("Idempotency-Key", "k-${UUID.randomUUID()}")
				.jsonBody("""{"recipeId":$recipe,"equipmentIds":[$running]}"""),
		).andReturn().response
		assertEquals(201, accepted.status, accepted.contentAsString)
	}
}
