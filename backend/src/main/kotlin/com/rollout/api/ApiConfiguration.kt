package com.rollout.api

import com.rollout.domain.Role
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter

/** 세션에 담는 것은 사용자 **id 하나**다 — 역할·이름은 매 요청 DB에서 다시 읽는다(불변 조건 3). */
const val SESSION_USER_ID = "rollout.uid"

/** 인증된 요청의 주체. 요청 속성으로도 실어 감사 필터가 시큐리티 컨텍스트 밖에서 읽는다. */
const val ACTOR = "rollout.actor"

data class Actor(val id: Long, val username: String, val displayName: String, val role: Role)

data class Account(val actor: Actor, val passwordHash: String, val enabled: Boolean)

@Repository
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class AccountRepository(private val jdbc: JdbcClient) {

	fun findByUsername(username: String): Account? =
		jdbc.sql("select id, username, display_name, role, password_hash, enabled from app_user where username = :u")
			.param("u", username)
			.query { rs, _ ->
				Account(
					Actor(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"), Role.valueOf(rs.getString("role"))),
					rs.getString("password_hash"),
					rs.getBoolean("enabled"),
				)
			}
			.optional()
			.orElse(null)

	/** 비활성화·삭제된 계정은 여기서 사라진다 — 기존 세션이 다음 요청부터 401이 되는 지점이다. */
	fun findEnabled(id: Long): Actor? =
		jdbc.sql("select id, username, display_name, role from app_user where id = :id and enabled = true")
			.param("id", id)
			.query { rs, _ -> Actor(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"), Role.valueOf(rs.getString("role"))) }
			.optional()
			.orElse(null)
}

/**
 * 매 요청 세션의 사용자 id로 `app_user`를 다시 읽는다(불변 조건 3). 역할 변경·비활성화가 다음 요청에
 * 즉시 반영되는 것이 요점이라 인증 결과를 세션에 캐시하지 않는다.
 */
class CurrentUserFilter(private val accounts: AccountRepository) : OncePerRequestFilter() {

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		val session = request.getSession(false)
		val userId = session?.getAttribute(SESSION_USER_ID) as Long?
		if (userId != null) {
			val actor = accounts.findEnabled(userId)
			if (actor == null) {
				session.invalidate()
			} else {
				request.setAttribute(ACTOR, actor)
				SecurityContextHolder.getContext().authentication =
					UsernamePasswordAuthenticationToken.authenticated(actor, null, emptyList())
			}
		}
		chain.doFilter(request, response)
	}
}

/** api 모드에서만 뜨는 빈(불변 조건 2). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class ApiConfiguration {

	/**
	 * 기본 거부 + JSON 401. 무인증 허용은 **정확히** 넷이다(불변 조건 3).
	 * CSRF 필터를 끄는 근거는 SameSite=Lax 세션 쿠키 + 상태 변경 GET 부재다
	 * (`AuthApiTest.noStateChangingGetInContract`가 계약의 GET 전부를 실호출해 행 수 불변으로 확인한다).
	 */
	@Bean
	fun securityFilterChain(http: HttpSecurity, accounts: AccountRepository): SecurityFilterChain =
		http
			.csrf { it.disable() }
			.httpBasic { it.disable() }
			.formLogin { it.disable() }
			.logout { it.disable() }
			.authorizeHttpRequests {
				it.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
					.requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health", "/actuator/prometheus").permitAll()
					.anyRequest().authenticated()
			}
			.exceptionHandling { handling ->
				handling.authenticationEntryPoint { _, response, _ ->
					response.status = 401
					response.contentType = MediaType.APPLICATION_JSON_VALUE
					response.characterEncoding = "UTF-8"
					response.writer.write("""{"code":"UNAUTHENTICATED","message":"인증이 필요하다"}""")
				}
			}
			.addFilterBefore(CurrentUserFilter(accounts), AuthorizationFilter::class.java)
			.build()

	@Bean
	fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

	/**
	 * 사용자는 DB(`app_user`)가 정본이고 자동설정의 기본 사용자는 쓰지 않는다 — 빈 매니저를 두지 않으면
	 * 기동 로그에 생성 비밀번호가 찍힌다(`scripts/smoke.sh`가 그 줄의 부재를 단언한다).
	 */
	@Bean
	fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()
}

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class HealthController {

	@GetMapping("/api/health")
	fun health(): Map<String, String> = mapOf("status" to "ok")
}
