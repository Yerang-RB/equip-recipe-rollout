package com.rollout.api

import com.rollout.job.toDb
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriUtils
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock

/**
 * 감사 기록의 유일한 쓰기 지점(불변 조건 9). `audit_log`는 append-only라 UPDATE·DELETE 경로가 없고,
 * `actor_name`은 그 시점의 표시 이름 스냅샷이다(개명해도 과거 행은 그대로다).
 */
@Service
class AuditWriter(private val jdbc: JdbcClient, private val clock: Clock, private val json: ObjectMapper) {

	fun write(actor: Actor?, action: String, targetType: String, targetId: Long?, result: String, detail: Map<String, Any?>) {
		jdbc.sql(
			"""
			insert into audit_log(at, actor_id, actor_name, action, target_type, target_id, detail, result)
			values (:at, :actorId, :actorName, :action, :targetType, :targetId, cast(:detail as jsonb), :result)
			""".trimIndent(),
		)
			.param("at", clock.instant().toDb())
			.param("actorId", actor?.id)
			.param("actorName", actor?.displayName ?: "anonymous")
			.param("action", action.take(COLUMN_MAX))
			.param("targetType", targetType.take(COLUMN_MAX))
			.param("targetId", targetId)
			.param("detail", json.writeValueAsString(detail))
			.param("result", result)
			.update()
	}

	/**
	 * 워커 대행 기록(설계 §8): 사람이 아니라 시스템이 설비에 썼다. 요청자는 `detail.on_behalf_of`로 남긴다.
	 * 호출자의 트랜잭션 안에서 쓴다 — task 상태 전이와 감사 행이 함께 커밋되거나 함께 사라진다.
	 */
	fun writeSystem(action: String, targetType: String, targetId: Long, result: String, onBehalfOf: Long, detail: Map<String, Any?>) {
		jdbc.sql(
			"""
			insert into audit_log(at, actor_id, actor_name, action, target_type, target_id, detail, result)
			values (:at, null, 'system', :action, :targetType, :targetId, cast(:detail as jsonb), :result)
			""".trimIndent(),
		)
			.param("at", clock.instant().toDb())
			.param("action", action)
			.param("targetType", targetType)
			.param("targetId", targetId)
			.param("detail", json.writeValueAsString(detail + ("on_behalf_of" to onBehalfOf)))
			.param("result", result)
			.update()
	}

	private companion object {
		/** `audit_log.action`·`target_type`의 열 너비 — 넘으면 insert가 죽어 기록이 통째로 빠진다(리뷰 AR3). */
		const val COLUMN_MAX = 50
	}
}

/**
 * 쓰기 오퍼레이션 **전부**를 한 자리에서 덮는다(불변 조건 9①) — 컨트롤러마다 부르면 나중에 생기는
 * 형제 경로가 조용히 빠진다. 시큐리티 체인 **바깥**에 두어 401·403도 결과로 본다.
 * 본문·질의문자열은 기록하지 않는다(자격증명이 섞이는 유일한 통로다 — 불변 조건 9③).
 */
class AuditFilter(private val writer: AuditWriter) : OncePerRequestFilter() {

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		chain.doFilter(request, response)
		if (request.method !in WRITE_METHODS || !request.requestURI.startsWith("/api/")) return
		// 디코딩한 경로로 정규화한다 — `/api/recipes/%33/approve`가 `{id}`로 접히지 않으면 행위 집계에서 빠지고
		// 긴 인코딩은 열 너비를 넘겨 기록 자체가 사라진다(리뷰 AR1·AR2). 깨진 인코딩은 원문 그대로 쓴다.
		val path = runCatching { UriUtils.decode(request.requestURI, StandardCharsets.UTF_8) }.getOrDefault(request.requestURI)
		val segments = path.removePrefix("/").split("/")
		val normalized = "/" + segments.joinToString("/") { if (it.toLongOrNull() != null) "{id}" else it }
		writer.write(
			actor = request.getAttribute(ACTOR) as Actor?,
			action = "${request.method} $normalized",
			targetType = segments.getOrElse(1) { "api" },
			targetId = segments.firstNotNullOfOrNull { it.toLongOrNull() },
			result = when {
				response.status < 300 -> "OK"
				response.status == 403 -> "DENIED"
				else -> "ERROR"
			},
			detail = mapOf("status" to response.status, "path" to normalized.take(PATH_MAX)),
		)
	}

	private companion object {
		val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

		/** 무인증으로도 닿는 기록 지점이라 경로 길이를 자른다(리뷰 AR3). */
		const val PATH_MAX = 200
	}
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class AuditFilterConfiguration {

	@Bean
	fun auditFilterRegistration(writer: AuditWriter): FilterRegistrationBean<AuditFilter> =
		FilterRegistrationBean(AuditFilter(writer)).apply {
			// 시큐리티 필터 체인(기본 순서 -100)보다 앞 — 감사가 401·403 응답까지 결과로 본다.
			order = -110
			addUrlPatterns("/*")
		}
}
