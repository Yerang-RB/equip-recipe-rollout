package com.rollout.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** bcrypt가 보는 것은 앞 72바이트뿐이다 — 그 뒤를 조용히 버리지 않고 서버가 거절한다(불변 조건 3). */
fun requirePasswordFits(password: String) {
	if (password.toByteArray().size > 72) invalid("비밀번호가 너무 길다")
}

fun meBody(actor: Actor): Map<String, Any> =
	mapOf("username" to actor.username, "displayName" to actor.displayName, "role" to actor.role.name)

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class AuthController(
	private val accounts: AccountRepository,
	private val encoder: PasswordEncoder,
) {

	data class LoginRequest(val username: String, val password: String)

	/**
	 * 없는 사용자와 틀린 비밀번호의 응답을 구별할 수 없게 한다 — 본문이 같을 뿐 아니라 **대조 비용도 같다**.
	 * 더미 해시는 실제 해시와 같은 인코더가 만든 것이라 cost factor가 같다(낮은 라운드면 타이밍이 다시 갈린다).
	 */
	val dummyHash: String = encoder.encode("존재하지 않는 계정을 위한 더미 비밀번호")!!

	@PostMapping("/api/auth/login")
	fun login(@RequestBody body: LoginRequest, request: HttpServletRequest): Map<String, Any> {
		requirePasswordFits(body.password)
		val account = accounts.findByUsername(body.username)
		val matched = encoder.matches(body.password, account?.passwordHash ?: dummyHash)
		if (!matched || account == null || !account.enabled) {
			throw ApiException(401, "UNAUTHENTICATED", "인증에 실패했다")
		}
		// 세션 고정 방어: 옛 세션을 버리고 새로 발급한다(쿠키 값이 바뀌고 옛 id는 죽는다).
		request.getSession(false)?.invalidate()
		request.getSession(true).setAttribute(SESSION_USER_ID, account.actor.id)
		// 감사 필터는 시큐리티 컨텍스트가 아니라 이 속성을 읽는다 — 로그인 성공도 주체가 있는 행이 된다.
		request.setAttribute(ACTOR, account.actor)
		return meBody(account.actor)
	}

	@PostMapping("/api/auth/logout")
	fun logout(@RequestAttribute(ACTOR) actor: Actor, request: HttpServletRequest): ResponseEntity<Void> {
		Policy.check(actor, Action.VIEW)
		request.getSession(false)?.invalidate()
		return ResponseEntity.noContent().build()
	}

	@GetMapping("/api/auth/me")
	fun me(@RequestAttribute(ACTOR) actor: Actor): Map<String, Any> {
		Policy.check(actor, Action.VIEW)
		return meBody(actor)
	}
}
