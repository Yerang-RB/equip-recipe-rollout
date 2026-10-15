package com.rollout

import com.rollout.api.SESSION_USER_ID
import com.rollout.domain.Role
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * API 테스트 공통 하네스. 네 테스트 클래스가 **같은 애노테이션**을 상속해 컨텍스트(그리고 임베디드
 * postgres)를 하나만 띄운다. 요청은 계약의 (메서드, 경로)로 만든다 — 손으로 적은 목록이 아니라
 * `Contract.operations`가 도는 것이 요점이다(불변 조건 1).
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
@AutoConfigureMockMvc
abstract class ApiTestSupport {

	@Autowired protected lateinit var mockMvc: MockMvc

	@Autowired protected lateinit var jdbc: JdbcTemplate

	@Autowired protected lateinit var encoder: PasswordEncoder

	@Autowired protected lateinit var json: ObjectMapper

	data class TestUser(val id: Long, val username: String, val displayName: String, val password: String, val role: Role)

	fun newUser(role: Role, password: String = "pw-${UUID.randomUUID()}"): TestUser {
		val username = "u-${UUID.randomUUID()}"
		val displayName = "이름-${UUID.randomUUID()}"
		val id = jdbc.queryForObject(
			"insert into app_user(username, password_hash, display_name, role, enabled, created_at) values (?, ?, ?, ?, true, now()) returning id",
			Long::class.java,
			username,
			encoder.encode(password),
			displayName,
			role.name,
		)!!
		return TestUser(id, username, displayName, password, role)
	}

	fun newEquipment(consolePassword: String = "cp-${UUID.randomUUID()}"): Long = jdbc.queryForObject(
		"insert into equipment(code, console_url, model, status, console_user, console_password, created_at) values (?, 'http://127.0.0.1:1/', 'M1', 'RUNNING', 'cu', ?, now()) returning id",
		Long::class.java,
		"E-${UUID.randomUUID()}",
		consolePassword,
	)!!

	fun newRecipe(createdBy: Long, status: String): Long = jdbc.queryForObject(
		"insert into recipe(part_no, version, params, status, created_by, created_at) values (?, 1, '{\"temp\":\"200\"}'::jsonb, ?, ?, now()) returning id",
		Long::class.java,
		"P-${UUID.randomUUID()}",
		status,
		createdBy,
	)!!

	fun newJob(requestedBy: Long): Long {
		val jobId = jdbc.queryForObject(
			"insert into rollout_job(recipe_id, status, idempotency_key, requested_by, created_at, updated_at) values (?, 'PENDING', ?, ?, now(), now()) returning id",
			Long::class.java,
			newRecipe(requestedBy, "ACTIVE"),
			"k-${UUID.randomUUID()}",
			requestedBy,
		)!!
		jdbc.update(
			"insert into rollout_task(job_id, equipment_id, status, attempt, created_at, updated_at) values (?, ?, 'PENDING', 0, now(), now())",
			jobId,
			newEquipment(),
		)
		return jobId
	}

	/**
	 * 세션에 담기는 것은 사용자 id 하나다 — 역할·이름은 서버가 매 요청 다시 읽는다.
	 * 세션 id를 직접 주는 것은 「세션 id가 감사·로그에 새지 않는다」를 찾을 수 있는 문자열로 만들기 위해서다.
	 */
	fun session(userId: Long, id: String = "sid-${UUID.randomUUID()}") =
		MockHttpSession(null, id).apply { setAttribute(SESSION_USER_ID, userId) }

	fun perform(op: Contract.Op, user: TestUser?, foreign: Boolean = false): MockHttpServletResponse =
		mockMvc.perform(requestFor(op, user, foreign)).andReturn().response

	@Suppress("UNCHECKED_CAST")
	fun bodyOf(response: MockHttpServletResponse): Map<String, Any?> =
		json.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

	fun codeOf(response: MockHttpServletResponse): String? = bodyOf(response)["code"] as String?

	/**
	 * @param foreign 잡 취소·재시도의 「자기 잡만」 조건을 밟기 위한 남의 잡 변형. 역할만으로는 거부를
	 *                만들 수 없는 칸(세 역할 모두 허용)의 거부 경로다.
	 */
	fun requestFor(op: Contract.Op, user: TestUser?, foreign: Boolean = false): MockHttpServletRequestBuilder {
		val owner = if (foreign || user == null) newUser(Role.OPERATOR).id else user.id
		val builder = when ("${op.method} ${op.path}") {
			"GET /api/health", "GET /api/auth/me", "GET /api/users", "GET /api/equipment",
			"GET /api/recipes", "GET /api/jobs", "GET /api/history", "GET /api/audit",
			-> get(op.path)

			"GET /api/recipes/{id}" -> get(op.path, newRecipe(owner, "DRAFT"))
			"GET /api/jobs/{id}" -> get(op.path, newJob(owner))
			"GET /api/ws/jobs/{id}" -> get(op.path, newJob(owner))

			"POST /api/auth/login" -> post(op.path)
				.jsonBody("""{"username":"${user?.username ?: "없는사용자"}","password":"${user?.password ?: "틀린비밀번호"}"}""")

			"POST /api/auth/logout" -> post(op.path)

			"POST /api/users" -> post(op.path)
				.jsonBody("""{"username":"u-${UUID.randomUUID()}","displayName":"새 사용자","role":"OPERATOR","password":"pw-${UUID.randomUUID()}"}""")

			"PUT /api/users/{id}" -> put(op.path, newUser(Role.OPERATOR).id)
				.jsonBody("""{"displayName":"바뀐 이름","role":"OPERATOR","enabled":true}""")

			"POST /api/equipment" -> post(op.path).jsonBody(equipmentJson())

			"PUT /api/equipment/{id}" -> put(op.path, newEquipment())
				.jsonBody("""{"consoleUrl":"http://127.0.0.1:1/","model":"M2","status":"MAINTENANCE","consoleUser":"cu"}""")

			"POST /api/recipes" -> post(op.path).jsonBody(recipeJson())
			"PUT /api/recipes/{id}" -> put(op.path, newRecipe(owner, "DRAFT")).jsonBody(recipeJson())

			// 승인 대상은 **다른 사람**의 초안이다 — 4-eyes는 경계 테스트가 따로 밟는다.
			"POST /api/recipes/{id}/approve" -> post(op.path, newRecipe(newUser(Role.APPROVER).id, "DRAFT"))
			"POST /api/recipes/{id}/retire" -> post(op.path, newRecipe(newUser(Role.APPROVER).id, "ACTIVE"))

			"POST /api/jobs" -> post(op.path)
				.header("Idempotency-Key", "k-${UUID.randomUUID()}")
				.jsonBody("""{"recipeId":${newRecipe(owner, "ACTIVE")},"equipmentIds":[${newEquipment()}]}""")

			"POST /api/jobs/{id}/cancel" -> post(op.path, newJob(owner))
			"POST /api/jobs/{id}/retry" -> post(op.path, newJob(owner))

			else -> error("계약에 새 오퍼레이션이 생겼는데 테스트 요청이 없다: $op")
		}
		user?.let { builder.session(session(it.id)) }
		return builder
	}

	fun equipmentJson(consolePassword: String = "cp-${UUID.randomUUID()}") =
		"""{"code":"E-${UUID.randomUUID()}","consoleUrl":"http://127.0.0.1:1/","model":"M1","status":"RUNNING","consoleUser":"cu","consolePassword":"$consolePassword"}"""

	fun recipeJson() = """{"partNo":"P-${UUID.randomUUID()}","version":1,"params":{"temp":"200"}}"""
}

fun MockHttpServletRequestBuilder.jsonBody(body: String): MockHttpServletRequestBuilder =
	contentType(MediaType.APPLICATION_JSON).content(body)
