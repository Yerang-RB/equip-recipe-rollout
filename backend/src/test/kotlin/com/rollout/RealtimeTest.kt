package com.rollout

import com.rollout.domain.HistoryResult
import com.rollout.domain.Role
import com.rollout.job.TaskOutcomeService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.sql.Connection
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 표식이 든 SQL의 실행 **횟수**를 세는 데이터소스. 목이 아니라 진짜 커넥션을 감싸므로 질의는 그대로 돈다.
 * 표식(`job-snapshot`)은 진행 스냅샷 문장에만 있어, 픽스처·업무 질의는 세지 않는다.
 */
class CountingDataSource(private val delegate: DataSource, private val marker: String) : DataSource by delegate {

	val count = AtomicInteger()

	override fun getConnection(): Connection = counting(delegate.connection)

	override fun getConnection(username: String?, password: String?): Connection =
		counting(delegate.getConnection(username, password))

	private fun counting(connection: Connection): Connection {
		val handler = InvocationHandler { _, method, args ->
			if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains(marker) == true) {
				count.incrementAndGet()
			}
			try {
				method.invoke(connection, *(args ?: emptyArray()))
			} catch (e: InvocationTargetException) {
				throw e.targetException
			}
		}
		return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java), handler) as Connection
	}
}

@TestConfiguration(proxyBeanMethods = false)
class CountingDataSourceConfiguration {

	@Bean
	@Primary
	fun countingDataSource(postgres: EmbeddedPostgres): CountingDataSource =
		CountingDataSource(postgres.postgresDatabase, "job-snapshot")
}

/** 프레임을 모아 한 줄씩 큐에 넣는 수신자(텍스트는 조각나 올 수 있다). */
class Collector : WebSocket.Listener {

	val messages: BlockingQueue<String> = LinkedBlockingQueue()

	private val buffer = StringBuilder()

	override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
		buffer.append(data)
		if (last) {
			messages.add(buffer.toString())
			buffer.setLength(0)
		}
		webSocket.request(1)
		return null
	}
}

/**
 * 실시간 진행(불변 조건 11). 워커의 커밋이 **폴링 없이** 구독자에게 닿는다는 것을 스냅샷 SQL 실행 수와
 * 푸시 수의 등호로 못박고, 핸드셰이크의 인증·Origin 거부를 실포트에서 본다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class, CountingDataSourceConfiguration::class)
@SpringBootTest(properties = ["mode=api"], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RealtimeTest : ApiTestSupport() {

	@Autowired private lateinit var counting: CountingDataSource

	@Autowired private lateinit var outcomes: TaskOutcomeService

	@Autowired private lateinit var mapper: ObjectMapper

	@LocalServerPort private var port: Int = 0

	private val http: HttpClient = HttpClient.newHttpClient()

	@Test
	fun taskCommitReachesWebSocketSubscriberWithoutPolling() {
		val user = newUser(Role.OPERATOR)
		val jobId = newJob(user.id)
		val taskId = jdbc.queryForObject("select id from rollout_task where job_id = ?", Long::class.java, jobId)!!
		jdbc.update("update rollout_task set status = 'CLAIMED', claimed_by = 'w-1', attempt = 1 where id = ?", taskId)

		counting.count.set(0)
		val collector = Collector()
		val socket = connect(jobId, cookie = loginCookie(user), listener = collector)
		try {
			assertEquals("CLAIMED", taskStatus(receive(collector)), "연결 직후 현재 스냅샷을 한 번 받는다")

			// 워커가 커밋하는 자리다 — 커밋 후 NOTIFY가 api의 LISTEN 커넥션으로 간다
			assertTrue(outcomes.succeed(taskId, "w-1", """{"temp":"200"}""", HistoryResult.APPLIED))

			val updated = receive(collector)
			assertEquals("SUCCEEDED", taskStatus(updated), "구독자가 갱신 스냅샷을 받는다")
			assertEquals("SUCCEEDED", snapshotOf(updated)["status"], "잡 상태도 함께 온다")

			assertEquals(2, counting.count.get(), "스냅샷 조회 수 == 푸시 수(연결 1 + 알림 1) — 폴링이면 더 크다")
		} finally {
			socket.abort()
		}
	}

	@Test
	fun unauthenticatedHandshakeIsRejected() {
		assertEquals(401, handshakeFailureStatus(cookie = null, origin = null))
	}

	@Test
	fun foreignOriginHandshakeIsRejected() {
		val user = newUser(Role.OPERATOR)
		val cookie = loginCookie(user)
		assertEquals(403, handshakeFailureStatus(cookie = cookie, origin = "http://evil.example"))

		// 양의 앵커: 허용 Origin(app.web-origin 기본값)으로는 같은 요청이 열린다
		val collector = Collector()
		val socket = connect(newJob(user.id), cookie = cookie, listener = collector, origin = "http://localhost:3000")
		try {
			assertNotNull(receive(collector))
		} finally {
			socket.abort()
		}
	}

	private fun handshakeFailureStatus(cookie: String?, origin: String?): Int {
		val failure = try {
			connect(newJob(newUser(Role.OPERATOR).id), cookie = cookie, listener = Collector(), origin = origin)
			fail("핸드셰이크가 열렸다")
		} catch (e: ExecutionException) {
			e.cause
		}
		return (failure as? WebSocketHandshakeException)?.response?.statusCode()
			?: fail("핸드셰이크 실패가 아니다: $failure")
	}

	private fun connect(jobId: Long, cookie: String?, listener: Collector, origin: String? = null): WebSocket {
		val builder = http.newWebSocketBuilder()
		cookie?.let { builder.header("Cookie", it) }
		origin?.let { builder.header("Origin", it) }
		return builder.buildAsync(URI("ws://127.0.0.1:$port/api/ws/jobs/$jobId"), listener).get(20, TimeUnit.SECONDS)
	}

	private fun receive(collector: Collector): String =
		collector.messages.poll(20, TimeUnit.SECONDS) ?: fail("스냅샷이 오지 않았다")

	@Suppress("UNCHECKED_CAST")
	private fun snapshotOf(message: String): Map<String, Any?> = mapper.readValue(message, Map::class.java) as Map<String, Any?>

	@Suppress("UNCHECKED_CAST")
	private fun taskStatus(message: String): String? {
		val tasks = snapshotOf(message)["tasks"] as List<Map<String, Any?>>
		return tasks.single()["status"] as String?
	}

	/** 핸드셰이크는 세션 쿠키로 인증한다 — 실포트로 로그인해 진짜 쿠키를 받는다. */
	private fun loginCookie(user: TestUser): String {
		val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""{"username":"${user.username}","password":"${user.password}"}"""))
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, response.statusCode(), response.body())
		return response.headers().allValues("set-cookie").single { it.startsWith("JSESSIONID=") }.substringBefore(";")
	}
}
