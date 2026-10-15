package com.rollout.api

import jakarta.annotation.PostConstruct
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * 잡 진행 스냅샷. SQL의 `job-snapshot` 주석은 **폴링 부재의 증거를 세는 표식**이다 —
 * `RealtimeTest`가 계수 데이터소스로 이 문장의 실행 수를 세어 푸시 수와 등호를 건다.
 * 문장은 하나다(잡 상태 + task 목록) — 둘로 쪼개면 그 등호가 깨진다.
 */
@Repository
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class JobSnapshotRepository(private val jdbc: JdbcClient) {

	fun of(jobId: Long): Map<String, Any?> {
		val rows = jdbc.sql(SQL)
			.param("id", jobId)
			.query { rs, _ ->
				Row(
					jobStatus = rs.getString("job_status"),
					task = mapOf(
						"equipmentCode" to rs.getString("code"),
						"status" to rs.getString("task_status"),
						"attempt" to rs.getInt("attempt"),
						"lastError" to rs.getString("last_error"),
					),
				)
			}
			.list()
		return mapOf(
			"jobId" to jobId,
			"status" to rows.firstOrNull()?.jobStatus,
			"tasks" to rows.map { it.task },
		)
	}

	private data class Row(val jobStatus: String, val task: Map<String, Any?>)

	private companion object {
		const val SQL = """
			select /* job-snapshot */ j.status as job_status, e.code, t.status as task_status, t.attempt, t.last_error
			from rollout_job j
			join rollout_task t on t.job_id = j.id
			join equipment e on e.id = t.equipment_id
			where j.id = :id order by t.id
		"""
	}
}

/**
 * 잡마다의 구독 세션. 연결 직후 현재 스냅샷을 한 번 보내고, 그 뒤로는 알림이 올 때만 보낸다.
 * 세션 인증은 핸드셰이크가 시큐리티 체인을 지나며 끝난다(무인증은 여기까지 오지 못한다).
 */
@Component
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class JobProgressHandler(private val snapshots: JobSnapshotRepository, private val json: ObjectMapper) : TextWebSocketHandler() {

	private val sessions = ConcurrentHashMap<Long, MutableSet<WebSocketSession>>()

	override fun afterConnectionEstablished(session: WebSocketSession) {
		val jobId = jobIdOf(session)
		if (jobId == null) {
			session.close(CloseStatus.BAD_DATA)
			return
		}
		sessions.computeIfAbsent(jobId) { ConcurrentHashMap.newKeySet() }.add(session)
		push(jobId, listOf(session))
	}

	override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
		jobIdOf(session)?.let { sessions[it]?.remove(session) }
	}

	/** 알림 하나당 스냅샷 조회도 하나다 — 구독자 수만큼 조회하지 않는다. */
	fun broadcast(jobId: Long) {
		val targets = sessions[jobId]?.toList().orEmpty().filter { it.isOpen }
		if (targets.isNotEmpty()) push(jobId, targets)
	}

	private fun push(jobId: Long, targets: List<WebSocketSession>) {
		val message = TextMessage(json.writeValueAsString(snapshots.of(jobId)))
		targets.forEach { session -> synchronized(session) { if (session.isOpen) session.sendMessage(message) } }
	}

	private fun jobIdOf(session: WebSocketSession): Long? = session.uri?.path?.substringAfterLast('/')?.toLongOrNull()
}

/**
 * `LISTEN rollout_progress` 전용 커넥션 하나(불변 조건 11의 「폴링 없음」). 워커가 커밋 후 보낸 `NOTIFY`를
 * 소켓에서 직접 받아 해당 잡의 구독자에게 넘긴다 — 주기적으로 DB를 되묻지 않는다.
 */
@Component
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class ProgressListener(private val dataSource: DataSource, private val handler: JobProgressHandler) : DisposableBean {

	private val log = LoggerFactory.getLogger(javaClass)

	@Volatile private var running = true

	@Volatile private var connection: Connection? = null

	private val thread = Thread(::listen, "progress-listener").apply { isDaemon = true }

	@PostConstruct
	fun start() = thread.start()

	override fun destroy() {
		running = false
		connection?.close()
		thread.join(5_000)
	}

	private fun listen() {
		try {
			dataSource.connection.use { conn ->
				connection = conn
				conn.createStatement().use { it.execute("listen rollout_progress") }
				val pg = conn.unwrap(PGConnection::class.java)
				while (running) {
					pg.getNotifications(POLL_MILLIS)?.forEach { notification ->
						notification.parameter.toLongOrNull()?.let(handler::broadcast)
					}
				}
			}
		} catch (e: Exception) {
			// 종료 중의 커넥션 닫힘은 정상 경로다 — 그때는 조용히 끝낸다.
			if (running) log.error("진행 알림 수신이 끊겼다: exception={}", e.javaClass.name)
		}
	}

	private companion object {
		/** 소켓 읽기 대기 시간이다(질의를 보내지 않는다) — 종료 플래그를 이 주기로 다시 본다. */
		const val POLL_MILLIS = 1_000
	}
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class RealtimeConfiguration(
	private val handler: JobProgressHandler,
	@param:Value("\${app.web-origin}") private val webOrigin: String,
) : WebSocketConfigurer {

	/** 허용 Origin은 하나뿐이다(불변 조건 16) — 다른 출처의 핸드셰이크는 403이다. */
	override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
		registry.addHandler(handler, "/api/ws/jobs/*").setAllowedOrigins(webOrigin)
	}
}
