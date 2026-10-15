package com.equipconsole

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.HtmlUtils
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 설비 벤더의 내장 웹 콘솔 목업(설계 §3). **우리 시스템이 아니다** — 경계를 빌드 구조로 못박으려고
 * 별도 모듈·별도 패키지(`com.equipconsole`)에 둔다. `com.rollout` 아래였다면 backend의 컴포넌트 스캔이
 * 이 빈들을 제 컨텍스트에 끌어들여, 「브라우저로 외부 시스템을 조작한다」는 주장이 흐려진다.
 *
 * 콘솔 한 대가 설비 한 대다 — 경로의 첫 마디(`/{station}/...`)가 설비이고 상태는 설비별로 따로 산다.
 */
@SpringBootApplication
class ConsoleMockApplication

/** 레시피 폼의 수치 5필드와 허용 범위(설계 §4 params 예의 다섯 — 결정 대기 2ⓙ). */
val NUMERIC_FIELDS: Map<String, IntRange> = linkedMapOf(
	"mold_temp_c" to 50..250,
	"inject_pressure_mpa" to 10..200,
	"hold_pressure_mpa" to 0..150,
	"cooling_sec" to 1..120,
	"cycle_sec" to 5..300,
)

private val TEXT_FIELDS = listOf("part_no", "version")

internal class Station {
	val values = ConcurrentHashMap<String, String>()
	/** 세션 토큰 → 마지막 사용 시각(ms). 서버 메모리뿐이라 재기동이 곧 폐기다. */
	val sessions = ConcurrentHashMap<String, Long>()
	val logins = AtomicInteger()
}

/**
 * 콘솔의 상태와 **결정적 주입**(불변 조건 12). 데모는 확률·지연(env)로 불안정하고, 테스트는 이 빈을
 * 직접 불러 「다음 저장 n번은 500」처럼 결정적으로 강제한다 — 확률에 기대는 테스트는 붙었다 떨어진다.
 */
@Component
class MockControl(
	@param:Value("\${MOCK_FAIL_RATE:0.10}") private val failRate: Double,
	@param:Value("\${MOCK_DELAY_MS:0}") initialDelayMs: Long,
	@param:Value("\${MOCK_SESSION_TTL_MS:0}") private val sessionTtlMs: Long,
) {

	/** 폼 응답 지연(ms). 0이면 지연 없음. */
	val delayMs = AtomicLong(initialDelayMs)

	private val stations = ConcurrentHashMap<String, Station>()
	private val failNext = AtomicInteger()
	private val corruptNext = AtomicInteger()
	private val expireAfter = AtomicInteger()
	private val inFlight = AtomicInteger()
	private val peakInFlight = AtomicInteger()

	fun failNextSaves(n: Int) = failNext.set(n)

	fun corruptNextSave() = corruptNext.set(1)

	/** n번째 **인증된** 요청에서 모든 세션을 버린다(한 번만). 세션 만료·재로그인 경로의 방아쇠다. */
	fun expireSessionsAfterRequests(n: Int) = expireAfter.set(n)

	/** 동시에 처리 중이던 요청 수의 최댓값 — 워커의 자기 신고가 아닌 **독립 관측**이다(불변 조건 8). */
	fun peakInFlight(): Int = peakInFlight.get()

	fun loginCount(station: String): Int = station(station).logins.get()

	fun savedRecipe(station: String): Map<String, String> = station(station).values.toMap()

	internal fun station(name: String): Station = stations.computeIfAbsent(name) { Station() }

	internal fun enter() {
		val now = inFlight.incrementAndGet()
		peakInFlight.getAndUpdate { maxOf(it, now) }
	}

	internal fun exit() {
		inFlight.decrementAndGet()
	}

	internal fun shouldFailSave(): Boolean {
		if (failNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return true
		return failRate > 0 && ThreadLocalRandom.current().nextDouble() < failRate
	}

	internal fun shouldCorruptSave(): Boolean = corruptNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0

	internal fun login(station: String, user: String, password: String, expectedUser: String, expectedPassword: String): String? {
		if (user != expectedUser || password != expectedPassword) return null
		val st = station(station)
		st.logins.incrementAndGet()
		val token = UUID.randomUUID().toString()
		st.sessions[token] = System.currentTimeMillis()
		return token
	}

	internal fun authenticated(station: String, token: String?): Boolean {
		if (token == null) return false
		val st = station(station)
		val lastSeen = st.sessions[token] ?: return false
		if (sessionTtlMs > 0 && System.currentTimeMillis() - lastSeen > sessionTtlMs) {
			st.sessions.remove(token)
			return false
		}
		st.sessions[token] = System.currentTimeMillis()
		if (expireAfter.get() > 0 && expireAfter.decrementAndGet() == 0) {
			stations.values.forEach { it.sessions.clear() }
			return false
		}
		return true
	}
}

/** 지연 주입과 in-flight 관측은 요청 전체를 감싸야 의미가 있다 — 필터가 그 자리다. */
@Component
class TrafficFilter(private val control: MockControl) : OncePerRequestFilter() {

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: jakarta.servlet.FilterChain) {
		control.enter()
		try {
			val delay = control.delayMs.get()
			if (delay > 0) Thread.sleep(delay)
			chain.doFilter(request, response)
		} finally {
			control.exit()
		}
	}
}

private const val SESSION_COOKIE = "CONSOLE_SESSION"

@RestController
class ConsoleController(
	private val control: MockControl,
	@param:Value("\${MOCK_CONSOLE_USER}") private val consoleUser: String,
	@param:Value("\${MOCK_CONSOLE_PASS}") private val consolePassword: String,
) {

	@GetMapping("/health")
	fun health(): Map<String, String> = mapOf("status" to "ok")

	@GetMapping("/{station}/login")
	fun loginPage(@PathVariable station: String): ResponseEntity<String> = html(loginHtml(station, error = null))

	@PostMapping("/{station}/login")
	fun login(
		@PathVariable station: String,
		@RequestParam username: String,
		@RequestParam password: String,
	): ResponseEntity<String> {
		val token = control.login(station, username, password, consoleUser, consolePassword)
			?: return html(loginHtml(station, "로그인 정보가 올바르지 않습니다"), HttpStatus.UNAUTHORIZED)
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, "/${enc(station)}/recipe")
			.header(HttpHeaders.SET_COOKIE, "$SESSION_COOKIE=$token; Path=/; HttpOnly")
			.build()
	}

	@GetMapping("/{station}/recipe")
	fun recipePage(
		@PathVariable station: String,
		@CookieValue(name = SESSION_COOKIE, required = false) token: String?,
	): ResponseEntity<String> {
		if (!control.authenticated(station, token)) return toLogin(station)
		return html(recipeHtml(station, control.savedRecipe(station), error = null))
	}

	@PostMapping("/{station}/recipe")
	fun saveRecipe(
		@PathVariable station: String,
		@CookieValue(name = SESSION_COOKIE, required = false) token: String?,
		@RequestParam form: Map<String, String>,
	): ResponseEntity<String> {
		if (!control.authenticated(station, token)) return toLogin(station)
		if (control.shouldFailSave()) {
			return html(recipeHtml(station, form, "설비 컨트롤러가 응답하지 않습니다"), HttpStatus.INTERNAL_SERVER_ERROR)
		}
		val values = LinkedHashMap<String, String>()
		for ((field, range) in NUMERIC_FIELDS) {
			val raw = form[field]?.trim().orEmpty()
			val number = raw.toBigDecimalOrNull()
				?: return html(recipeHtml(station, form, "$field 값이 숫자가 아닙니다: $raw"), HttpStatus.BAD_REQUEST)
			if (number < BigDecimal(range.first) || number > BigDecimal(range.last)) {
				val message = "$field 값이 허용 범위 ${range.first}..${range.last}를 벗어났습니다: $raw"
				return html(recipeHtml(station, form, message), HttpStatus.BAD_REQUEST)
			}
			values[field] = number.toPlainString()
		}
		TEXT_FIELDS.forEach { values[it] = form[it]?.trim().orEmpty() }
		// 「저장했다」는 자기 신고를 믿지 않는지 재는 주입: 200인데 값이 다르게 들어간다(설계 §5 3번)
		if (control.shouldCorruptSave()) {
			values["cooling_sec"] = (BigDecimal(values.getValue("cooling_sec")) + BigDecimal.ONE).toPlainString()
		}
		val saved = control.station(station).values
		saved.clear()
		saved.putAll(values)
		return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/${enc(station)}/recipe").build()
	}

	private fun toLogin(station: String): ResponseEntity<String> =
		ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/${enc(station)}/login").build()

	private fun html(body: String, status: HttpStatus = HttpStatus.OK): ResponseEntity<String> =
		ResponseEntity.status(status).contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(body)
}

private fun enc(value: String) = HtmlUtils.htmlEscape(value)

private fun loginHtml(station: String, error: String?) = """
	<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${enc(station)} 콘솔 로그인</title></head>
	<body><h1>${enc(station)} 설비 콘솔</h1>
	${error?.let { "<p class=\"error\">${enc(it)}</p>" } ?: ""}
	<form method="post" action="login">
	<label>사용자 <input name="username"></label>
	<label>비밀번호 <input name="password" type="password"></label>
	<button type="submit">로그인</button>
	</form></body></html>
""".trimIndent()

private fun recipeHtml(station: String, values: Map<String, String>, error: String?) = """
	<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>${enc(station)} 레시피</title></head>
	<body><h1>${enc(station)} 성형 조건</h1>
	${error?.let { "<p class=\"error\">${enc(it)}</p>" } ?: ""}
	<form method="post" action="recipe">
	${(TEXT_FIELDS + NUMERIC_FIELDS.keys).joinToString("\n") {
		"<label>$it <input name=\"$it\" value=\"${enc(values[it].orEmpty())}\"></label>"
	}}
	<button type="submit">저장</button>
	</form></body></html>
""".trimIndent()

fun main(args: Array<String>) {
	runApplication<ConsoleMockApplication>(*args)
}
