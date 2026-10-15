package com.rollout

import com.rollout.domain.Role
import com.rolloutprobe.ProbeController
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * 에러 규약(불변 조건 4)의 세 자리: 없는 경로·미지 필드 · 본문 상한(파싱 전) · 멀티파트 우회 경로.
 * 상한은 **실포트 chunked**로 본다 — `Content-Length` 검사로는 못 막는 경로이고, MockMvc에는 그 경로가 없다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class, ProbeController::class)
@SpringBootTest(properties = ["mode=api"], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ErrorHandlingTest : ApiTestSupport() {

	@Autowired private lateinit var probe: ProbeController

	@LocalServerPort private var port: Int = 0

	private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

	@BeforeEach
	fun resetProbe() {
		probe.bodyCalls.set(0)
	}

	/**
	 * 413 ∧ **핸들러 호출 0**이 「파싱 전에 잘랐다」의 증거다 — 상태코드만으로는 파싱 후 거부와 구별되지 않는다.
	 */
	@Test
	fun oversizedChunkedBodyIsRejectedBeforeParsing() {
		val cookie = loginCookie()

		// 양의 앵커: 같은 발행자(길이 미상 = chunked)로 보낸 작은 본문은 핸들러까지 간다
		val small = sendChunked(cookie, """{"name":"작다"}""")
		assertEquals(200, small.statusCode(), small.body())
		assertEquals(1, probe.bodyCalls.get())

		val big = sendChunked(cookie, """{"name":"${"a".repeat(5 * 1024 * 1024)}"}""")
		assertEquals(413, big.statusCode(), big.body())
		assertContains(big.body(), "\"code\":\"PAYLOAD_TOO_LARGE\"")
		assertEquals(1, probe.bodyCalls.get(), "초과 본문이 핸들러까지 갔다")
	}

	/** 멀티파트는 본문 상한 스트림 밖의 읽기 경로라 프로퍼티로 닫혀 있다 — 파싱 없이 400이다. */
	@Test
	fun multipartBodyIsRejectedWithoutParsing() {
		val payload = "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n${"a".repeat(1024 * 1024)}\r\n--b--\r\n"
		val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/probe/body"))
			.header("Content-Type", "multipart/form-data; boundary=b")
			.header("Cookie", loginCookie())
			.POST(HttpRequest.BodyPublishers.ofString(payload))
			.build()
		val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(60, TimeUnit.SECONDS)

		assertEquals(400, response.statusCode(), response.body())
		assertContains(response.body(), "\"code\":\"INVALID_REQUEST\"")
		assertEquals(0, probe.bodyCalls.get(), "멀티파트 본문이 핸들러까지 갔다")
	}

	@Test
	fun unknownPathIs404AndUnknownFieldIs400() {
		val session = session(newUser(Role.OPERATOR).id)

		val unknownPath = mockMvc.perform(get("/api/nope").session(session)).andReturn().response
		assertEquals(404, unknownPath.status, unknownPath.contentAsString)
		assertEquals("NOT_FOUND", codeOf(unknownPath))

		val unknownField = mockMvc.perform(
			post("/probe/body").session(session).jsonBody("""{"name":"a","extra":1}"""),
		).andReturn().response
		assertEquals(400, unknownField.status, unknownField.contentAsString)
		assertEquals("INVALID_REQUEST", codeOf(unknownField))
		assertEquals(0, probe.bodyCalls.get(), "미지 필드가 핸들러까지 갔다")

		// 양의 앵커: 같은 경로의 올바른 본문은 핸들러까지 간다
		val valid = mockMvc.perform(post("/probe/body").session(session).jsonBody("""{"name":"a"}""")).andReturn().response
		assertEquals(200, valid.status, valid.contentAsString)
		assertEquals(1, probe.bodyCalls.get())
	}

	private fun sendChunked(cookie: String, body: String): HttpResponse<String> {
		val bytes = body.toByteArray()
		val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/probe/body"))
			.header("Content-Type", "application/json")
			.header("Cookie", cookie)
			// 길이 미상 스트림은 `Transfer-Encoding: chunked`로 나간다(Content-Length 검사가 못 보는 경로)
			.POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) })
			.build()
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(60, TimeUnit.SECONDS)
	}

	private fun loginCookie(): String {
		val user = newUser(Role.OPERATOR)
		val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""{"username":"${user.username}","password":"${user.password}"}"""))
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, response.statusCode(), response.body())
		return response.headers().allValues("set-cookie").single { it.startsWith("JSESSIONID=") }.substringBefore(";")
	}
}
