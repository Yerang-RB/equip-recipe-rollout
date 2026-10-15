package com.rollout

import com.rollout.api.AuthController
import com.rollout.domain.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 해시 대조 **횟수**를 세는 데코레이터. 목이 아니라 진짜 인코더를 감싸므로 비용도 그대로다. */
class CountingPasswordEncoder(private val delegate: PasswordEncoder) : PasswordEncoder {

	val matchCount = AtomicInteger()

	override fun encode(rawPassword: CharSequence?): String? = delegate.encode(rawPassword)

	override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean {
		matchCount.incrementAndGet()
		return delegate.matches(rawPassword, encodedPassword)
	}
}

@TestConfiguration(proxyBeanMethods = false)
class CountingEncoderConfiguration {

	@Bean
	@Primary
	fun countingPasswordEncoder(): CountingPasswordEncoder = CountingPasswordEncoder(BCryptPasswordEncoder())
}

/**
 * 인증의 보안 불변조건(불변 조건 3). 쿠키 속성·세션 회전은 **실포트의 실제 `Set-Cookie`**로 본다 —
 * MockMvc는 그 헤더를 만들지 않아 통과해도 아무것도 지키지 않는다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class, CountingEncoderConfiguration::class)
@SpringBootTest(properties = ["mode=api"], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthApiTest : ApiTestSupport() {

	@Autowired private lateinit var counting: CountingPasswordEncoder

	@Autowired private lateinit var authController: AuthController

	@LocalServerPort private var port: Int = 0

	private val http: HttpClient = HttpClient.newHttpClient()

	@Test
	fun unknownUserAndWrongPasswordReturnIdenticalBodyAndOneHashCompare() {
		val user = newUser(Role.OPERATOR)

		counting.matchCount.set(0)
		val wrongPassword = login(user.username, "틀린 비밀번호")
		assertEquals(1, counting.matchCount.get(), "틀린 비밀번호는 한 번 대조한다")

		counting.matchCount.set(0)
		val unknownUser = login("없는사용자-${user.username}", "틀린 비밀번호")
		assertEquals(1, counting.matchCount.get(), "없는 사용자도 **한 번** 대조한다(더미 해시)")

		assertEquals(401, wrongPassword.status)
		assertEquals(401, unknownUser.status)
		assertEquals(
			wrongPassword.contentAsByteArray.toList(),
			unknownUser.contentAsByteArray.toList(),
			"두 실패의 본문 바이트가 같다(존재 은닉)",
		)

		val storedHash = jdbc.queryForObject("select password_hash from app_user where id = ?", String::class.java, user.id)!!
		assertEquals(cost(storedHash), cost(authController.dummyHash), "더미 해시의 cost factor가 실제 해시와 같다")
	}

	@Test
	fun loginRotatesSessionIdAndSetsHttpOnlySameSiteLaxOverRealPort() {
		val user = newUser(Role.OPERATOR)

		val first = realLogin(user, cookie = null)
		val firstCookie = setCookie(first)
		assertEquals(200, first.statusCode())
		assertContains(firstCookie, "HttpOnly")
		assertContains(firstCookie, "SameSite=Lax")

		val firstSession = sessionCookie(firstCookie)
		val second = realLogin(user, cookie = firstSession)
		assertEquals(200, second.statusCode())
		val secondSession = sessionCookie(setCookie(second))
		assertNotEquals(firstSession, secondSession, "로그인은 세션 id를 새로 발급한다(세션 고정 방어)")

		val withOldSession = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth/me"))
			.header("Cookie", firstSession)
			.GET()
			.build()
		val reused = http.send(withOldSession, HttpResponse.BodyHandlers.ofString())
		assertEquals(401, reused.statusCode(), "옛 세션 id는 죽었다")
		assertContains(reused.body(), "UNAUTHENTICATED")

		val withNewSession = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth/me"))
			.header("Cookie", secondSession)
			.GET()
			.build()
		assertEquals(200, http.send(withNewSession, HttpResponse.BodyHandlers.ofString()).statusCode(), "양의 앵커: 새 세션은 산다")
	}

	@Test
	fun passwordOver72BytesIsRejectedWith400() {
		val user = newUser(Role.OPERATOR)

		val tooLong = login(user.username, "a".repeat(73))
		assertEquals(400, tooLong.status)
		assertEquals("INVALID_REQUEST", codeOf(tooLong))

		// 경계 바로 아래는 통과해 인증 판정까지 간다(전부 400으로 막는 코드가 아니다)
		assertEquals(401, login(user.username, "a".repeat(72)).status)
	}

	@Test
	fun everyNonPublicEndpointRejectsUnauthenticatedWithJson401() {
		val nonPublic = Contract.operations.filter { !it.public }
		assertEquals(22, nonPublic.size, "계약의 인증 오퍼레이션 수")

		nonPublic.forEach { op ->
			val response = mockMvc.perform(bare(op)).andReturn().response
			assertEquals(401, response.status, "$op 는 무인증이면 401")
			assertEquals("UNAUTHENTICATED", codeOf(response), "$op")
			assertTrue(response.contentType!!.startsWith("application/json"), "$op → ${response.contentType}")
		}
	}

	@Test
	fun disabledUserSessionIsRejectedImmediately() {
		val user = newUser(Role.OPERATOR)
		val admin = newUser(Role.ADMIN)

		val loggedIn = mockMvc.perform(
			post("/api/auth/login").jsonBody("""{"username":"${user.username}","password":"${user.password}"}"""),
		).andReturn()
		assertEquals(200, loggedIn.response.status)
		val session = loggedIn.request.getSession(false) as MockHttpSession
		assertEquals(200, mockMvc.perform(get("/api/auth/me").session(session)).andReturn().response.status)

		assertEquals(
			200,
			mockMvc.perform(
				put("/api/users/{id}", user.id).session(session(admin.id))
					.jsonBody("""{"displayName":"${user.displayName}","role":"OPERATOR","enabled":false}"""),
			).andReturn().response.status,
		)

		val afterDisable = mockMvc.perform(get("/api/auth/me").session(session)).andReturn().response
		assertEquals(401, afterDisable.status, "같은 쿠키의 다음 요청부터 401")
		assertEquals("UNAUTHENTICATED", codeOf(afterDisable))
	}

	/** CSRF 필터를 끈 근거의 절반 — 계약의 GET은 전부 조회다(어느 표의 행 수도 바뀌지 않는다). */
	@Test
	fun noStateChangingGetInContract() {
		val admin = newUser(Role.ADMIN)
		val before = rowCounts()

		Contract.operations.filter { it.method == "GET" }.forEach { op ->
			mockMvc.perform(bare(op).session(session(admin.id))).andReturn()
		}

		assertEquals(before, rowCounts(), "GET 호출 뒤 어느 표의 행 수도 그대로다")
	}

	/** 픽스처를 만들지 않는 맨 요청 — 행 수 불변 단언이 자기 준비 때문에 깨지지 않게 한다. */
	private fun bare(op: Contract.Op) = when (op.method) {
		"GET" -> get(op.path.replace("{id}", "1"))
		"POST" -> post(op.path.replace("{id}", "1")).jsonBody("{}")
		"PUT" -> put(op.path.replace("{id}", "1")).jsonBody("{}")
		else -> error("계약에 새 메서드가 생겼다: $op")
	}

	private fun rowCounts(): Map<String, Int> = TABLES.associateWith {
		jdbc.queryForObject("select count(*)::int from $it", Int::class.java)!!
	}

	private fun login(username: String, password: String) = mockMvc.perform(
		post("/api/auth/login").jsonBody("""{"username":"$username","password":"$password"}"""),
	).andReturn().response

	private fun realLogin(user: TestUser, cookie: String?): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""{"username":"${user.username}","password":"${user.password}"}"""))
		cookie?.let { builder.header("Cookie", it) }
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	private fun setCookie(response: HttpResponse<String>): String =
		response.headers().allValues("set-cookie").single { it.startsWith("JSESSIONID=") }

	private fun sessionCookie(setCookie: String): String = setCookie.substringBefore(";")

	/** bcrypt 해시는 `$2a$<cost>$...` 꼴이다. */
	private fun cost(hash: String): String = hash.split("$")[2]

	private companion object {
		val TABLES = listOf("app_user", "equipment", "recipe", "rollout_job", "rollout_task", "application_history", "audit_log")
	}
}
