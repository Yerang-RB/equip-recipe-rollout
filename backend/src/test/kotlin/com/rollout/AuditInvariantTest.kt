package com.rollout

import com.rollout.api.Action
import com.rollout.domain.Role
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataAccessException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.net.URI
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 감사 불변식 4개(설계 §8 · 불변 조건 9)를 전부 단언한다. 쓰기 오퍼레이션 목록은 계약에서 읽으므로,
 * 나중에 엔드포인트가 늘면 감사 없이 통과할 수 없다.
 */
@ExtendWith(OutputCaptureExtension::class)
class AuditInvariantTest : ApiTestSupport() {

	/** 불변식 ① — 성공과 거부 양쪽. */
	@Test
	fun everyWriteEndpointAuditsDeniedAndSuccessfulAttempts() {
		val writes = Contract.writeOperations
		val deniable = writes.filter { deniedCaller(it) != null }
		assertEquals(13, writes.size, "계약의 쓰기 오퍼레이션 수")
		assertEquals(11, deniable.size, "거부가 가능한 쓰기 오퍼레이션(로그인·로그아웃 제외)")
		val baseline = maxAuditId()

		val withOk = writes.filter { op ->
			val response = perform(op, allowedCaller(op))
			assertTrue(response.status in 200..299, "$op 는 허용 역할로 성공해야 한다: ${response.status} ${response.contentAsString}")
			auditRows(baseline, op, "OK") > 0
		}
		val withDenied = deniable.filter { op ->
			val (caller, foreign) = deniedCaller(op)!!
			val response = perform(op, caller, foreign)
			assertEquals(403, response.status, "$op 는 거부돼야 한다: ${response.contentAsString}")
			auditRows(baseline, op, "DENIED") > 0
		}

		assertEquals(writes.toSet(), withOk.toSet(), "쓰기 오퍼레이션 전부에 OK 감사 행이 있다")
		assertEquals(deniable.toSet(), withDenied.toSet(), "거부 가능한 쓰기 오퍼레이션 전부에 DENIED 감사 행이 있다")
	}

	@Test
	fun loginFailureIsAudited() {
		val user = newUser(Role.OPERATOR)
		val baseline = maxAuditId()

		val response = mockMvc.perform(
			post("/api/auth/login").jsonBody("""{"username":"${user.username}","password":"틀린 비밀번호"}"""),
		).andReturn().response

		assertEquals(401, response.status)
		assertEquals(1, auditRows(baseline, Contract.Op("POST", "/api/auth/login", public = true), "ERROR"))
	}

	/**
	 * 리뷰 AR1·AR2 — 퍼센트 인코딩한 id로 승인해도 같은 행위로 기록된다. 수정 전에는 디코딩 안 된 경로가
	 * `action` 열(50자)을 넘겨 감사 insert가 죽었다 — 승인은 커밋되고 감사 행은 없고 응답은 401이었다.
	 * 인코딩 경로를 테스트가 직접 만든다(정상 경로 문자열만 밟는 전수 테스트는 이 모양을 못 본다).
	 */
	@Test
	fun percentEncodedPathIsAuditedAsTheSameOperation() {
		val approver = newUser(Role.APPROVER)
		val recipeId = newRecipe(newUser(Role.OPERATOR).id, "DRAFT")
		val encodedId = "%30".repeat(12) + recipeId.toString().map { "%3$it" }.joinToString("")
		val baseline = maxAuditId()

		val response = mockMvc.perform(
			post(URI.create("/api/recipes/$encodedId/approve")).session(session(approver.id)),
		).andReturn().response

		assertEquals(200, response.status, response.contentAsString)
		val op = Contract.Op("POST", "/api/recipes/{id}/approve", public = false)
		assertEquals(1, auditRows(baseline, op, "OK"), "인코딩 경로도 정규화된 행위로 한 행")
		assertEquals(recipeId, jdbc.queryForObject("select target_id from audit_log where id = ?", Long::class.java, maxAuditId()))
	}

	/** 리뷰 AR3 — 무인증 긴 경로도 기록이 빠지지 않는다(열 너비에 맞춰 자른다). */
	@Test
	fun longUnauthenticatedPathIsStillAudited(output: CapturedOutput) {
		val baseline = maxAuditId()
		val path = "/api/" + "a".repeat(300)

		val response = mockMvc.perform(post(path)).andReturn().response

		assertEquals(401, response.status)
		val rows = jdbc.queryForList("select action, result from audit_log where id > ?", baseline)
		assertEquals(1, rows.size, "행이 하나 남는다: $rows")
		assertEquals(50, (rows.single()["action"] as String).length)
		assertEquals("ERROR", rows.single()["result"])
		assertFalse("\"level\":\"ERROR\"" in output.all, "감사 insert 실패로 ERROR 로그가 찍히지 않는다")
	}

	/** 불변식 ② — append-only는 DB 트리거가 막고, 운영 코드에는 그 문장 자체가 없다. */
	@Test
	fun auditLogHasNoUpdateOrDeletePath() {
		val grep = ProcessBuilder("grep", "-rniE", "update audit_log|delete from audit_log", "backend/src/main")
			.directory(Contract.repoRoot)
			.redirectErrorStream(true)
			.start()
		val matches = grep.inputStream.bufferedReader().readText()
		assertEquals(1, grep.waitFor(), "운영 코드에 audit_log UPDATE/DELETE 문장이 없다(grep 매치): $matches")

		// 양의 앵커 겸 트리거 확인: 행이 실제로 있고, 그 행을 고치려 들면 DB가 막는다.
		val admin = newUser(Role.ADMIN)
		assertEquals(201, mockMvc.perform(post("/api/equipment").session(session(admin.id)).jsonBody(equipmentJson())).andReturn().response.status)
		val id = maxAuditId()
		assertViolation("P0001", "update audit_log set result = 'OK' where id = ?", id)
		assertViolation("P0001", "delete from audit_log where id = ?", id)
	}

	/** 불변식 ③ — 자격증명은 감사·응답·로그 어디에도 없다(양의 앵커와 함께). */
	@Test
	fun detailNeverContainsCredentials(output: CapturedOutput) {
		val password = "비밀번호-${UUID.randomUUID()}"
		val consolePassword = "콘솔비밀번호-${UUID.randomUUID()}"
		val admin = newUser(Role.ADMIN, password)
		val sessionId = "sid-${UUID.randomUUID()}"
		val baseline = maxAuditId()

		val created = mockMvc.perform(
			post("/api/equipment").session(session(admin.id, sessionId)).jsonBody(equipmentJson(consolePassword)),
		).andReturn().response
		assertEquals(201, created.status)
		val loggedIn = mockMvc.perform(
			post("/api/auth/login").jsonBody("""{"username":"${admin.username}","password":"$password"}"""),
		).andReturn().response
		assertEquals(200, loggedIn.status)
		val hash = jdbc.queryForObject("select password_hash from app_user where id = ?", String::class.java, admin.id)!!

		// 양의 앵커: 같은 행위의 감사 행이 실제로 남았다(빈 감사 로그로 통과하지 않는다).
		assertEquals(1, auditRows(baseline, Contract.Op("POST", "/api/equipment", public = false), "OK"))
		assertEquals(1, auditRows(baseline, Contract.Op("POST", "/api/auth/login", public = true), "OK"))

		val secrets = listOf(password, consolePassword, sessionId, hash)
		val details = jdbc.queryForList("select cast(detail as text) as detail from audit_log", String::class.java).filterNotNull()
		secrets.forEach { secret ->
			details.forEach { detail -> assertFalse(secret in detail, "감사 detail에 비밀값이 없다: $detail") }
			assertFalse(secret in created.contentAsString, "응답에 비밀값이 없다: ${created.contentAsString}")
			assertFalse(secret in loggedIn.contentAsString, "응답에 비밀값이 없다: ${loggedIn.contentAsString}")
			assertFalse(secret in output.all, "로그에 비밀값이 없다")
		}
	}

	/** 불변식 ④ — `actor_name`은 시점 스냅샷이라 개명해도 과거 행이 바뀌지 않는다. */
	@Test
	fun actorNameIsSnapshotAfterRename() {
		val admin = newUser(Role.ADMIN)
		val author = newUser(Role.OPERATOR)
		val before = maxAuditId()

		assertEquals(201, mockMvc.perform(post("/api/recipes").session(session(author.id)).jsonBody(recipeJson())).andReturn().response.status)
		val firstRow = maxAuditId()

		val renamed = "개명한 이름-${UUID.randomUUID()}"
		assertEquals(
			200,
			mockMvc.perform(
				put("/api/users/{id}", author.id).session(session(admin.id))
					.jsonBody("""{"displayName":"$renamed","role":"OPERATOR","enabled":true}"""),
			).andReturn().response.status,
		)
		assertEquals(201, mockMvc.perform(post("/api/recipes").session(session(author.id)).jsonBody(recipeJson())).andReturn().response.status)

		assertTrue(firstRow > before)
		assertEquals(author.displayName, actorName(firstRow), "과거 행은 그때의 이름 그대로다")
		assertEquals(renamed, actorName(maxAuditId()), "개명 뒤의 행은 새 이름이다")
	}

	private fun allowedCaller(op: Contract.Op): TestUser {
		val action = ExpectedMatrix.actionOf[op] ?: return newUser(Role.OPERATOR) // 무인증 로그인은 자기 자격증명으로 성공한다
		return newUser(ExpectedMatrix.allowed.getValue(action).first())
	}

	/** 거부를 만들 수 있는 호출자 — 세 역할 모두 허용인 칸은 소유자 조건(남의 잡)으로만 거부가 생긴다. */
	private fun deniedCaller(op: Contract.Op): Pair<TestUser, Boolean>? {
		val action = ExpectedMatrix.actionOf[op] ?: return null
		ExpectedMatrix.denyingRole(action)?.let { return newUser(it) to false }
		return if (action == Action.JOB_CANCEL_RETRY) newUser(Role.OPERATOR) to true else null
	}

	private fun maxAuditId(): Long = jdbc.queryForObject("select coalesce(max(id), 0) from audit_log", Long::class.java)!!

	private fun actorName(auditId: Long) = jdbc.queryForObject("select actor_name from audit_log where id = ?", String::class.java, auditId)

	private fun auditRows(baseline: Long, op: Contract.Op, result: String): Int = jdbc.queryForObject(
		"select count(*)::int from audit_log where id > ? and action = ? and result = ?",
		Int::class.java,
		baseline,
		"${op.method} ${op.path}",
		result,
	)!!

	private fun assertViolation(sqlState: String, sql: String, vararg args: Any?) {
		try {
			jdbc.update(sql, *args)
		} catch (e: DataAccessException) {
			assertEquals(sqlState, (e.mostSpecificCause as? SQLException)?.sqlState, "SQLSTATE: ${e.mostSpecificCause}")
			return
		}
		fail("append-only를 어기는 문장이 성공했다: $sql")
	}
}
