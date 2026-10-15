package com.rollout.worker

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import com.rollout.job.FailureKind
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 사람이 브라우저로 하던 일을 그대로 한다(설계 §5 3번): 로그인 → 폼 입력 → 저장 → **저장 후 재조회 대조**.
 * 마지막 단계가 이 어댑터의 품질 주장이다 — 저장 버튼을 눌렀다는 것과 값이 들어갔다는 것은 다르다.
 *
 * 스레드 규약: Playwright 객체는 스레드 안전하지 않다. 그래서 「Playwright + 브라우저 + 설비별 컨텍스트」
 * 한 벌을 슬롯으로 묶고, 한 슬롯은 한 스레드가 배타적으로 쥔다(큐에서 꺼내 쓰고 되돌려 놓는다).
 * 동시 슬롯 수의 상한은 `ClaimLoop`의 세마포어다(불변 조건 8).
 */
@Component
@ConditionalOnProperty(name = ["mode"], havingValue = "worker")
class PlaywrightRecipeApplier(meters: MeterRegistry) : RecipeApplier, AutoCloseable {

	private val idle = ConcurrentLinkedQueue<Slot>()
	private val all = ConcurrentLinkedQueue<Slot>()

	/** 지금 콘솔을 조작하고 있는 세션 수(불변 조건 11의 게이지) — 상한을 지키는지 재는 자리다. */
	private val active = AtomicInteger()
	private val peak = AtomicInteger()

	init {
		meters.gauge("browser_sessions_active", active)
	}

	/** 동시 세션 수의 최댓값. 테스트가 상한과 **등호**로 대조한다(불변 조건 8). */
	fun peakSessions(): Int = peak.get()

	private class Slot {
		// 이 env가 없으면 create()가 firefox/webkit을 받으러 가서 돌아오지 않는다(샌드박스 실측)
		val playwright: Playwright =
			Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))
		val browser: Browser = playwright.chromium().launch()
		/** 설비별 컨텍스트 재사용 = 세션 쿠키 유지(불변 조건 6) — 매 task마다 로그인하지 않는다. */
		val contexts = ConcurrentHashMap<Long, BrowserContext>()
	}

	override fun apply(target: ConsoleTarget, recipe: RecipeToApply) {
		// ponytail: 슬롯이 달라지면 같은 설비라도 컨텍스트 캐시가 빗나가 로그인을 한 번 더 한다.
		// 설비 수가 늘면 슬롯을 설비로 고정 배정할 것. 지금은 여분 로그인 한 번이 값싸다.
		val slot = idle.poll() ?: Slot().also { all.add(it) }
		val now = active.incrementAndGet()
		peak.getAndUpdate { maxOf(it, now) }
		try {
			val context = slot.contexts.computeIfAbsent(target.equipmentId) { slot.browser.newContext() }
			try {
				applyOn(context, target, recipe)
			} catch (e: Throwable) {
				// 실패한 컨텍스트는 상태를 믿을 수 없다 — 버리고 다음 시도는 새 세션으로 연다
				slot.contexts.remove(target.equipmentId)
				runCatching { context.close() }
				throw e
			}
		} finally {
			active.decrementAndGet()
			idle.add(slot)
		}
	}

	private fun applyOn(context: BrowserContext, target: ConsoleTarget, recipe: RecipeToApply) {
		val page = context.newPage()
		try {
			page.navigate("${target.baseUrl}/recipe")
			if (onLoginPage(page)) login(page, target)
			fillForm(page, recipe)
			var status = save(page)
			if (onLoginPage(page)) {
				// 세션 만료(저장 응답이 로그인 페이지) — 재로그인 후 **한 번만** 다시 시도한다
				login(page, target)
				fillForm(page, recipe)
				status = save(page)
				if (onLoginPage(page)) throw ApplyFailure(FailureKind.SESSION_EXPIRED, "재로그인 후에도 세션이 유지되지 않았다")
			}
			when {
				status == 400 -> throw ApplyFailure(FailureKind.VALUE_REJECTED, errorText(page))
				status >= 500 -> throw ApplyFailure(FailureKind.SERVER_ERROR, "콘솔 저장 응답 $status: ${errorText(page)}")
				status != 200 -> throw ApplyFailure(FailureKind.SERVER_ERROR, "콘솔 저장 응답 $status")
			}
			verify(page, target, recipe)
		} catch (e: TimeoutError) {
			throw ApplyFailure(FailureKind.TIMEOUT, "콘솔 응답 대기 시간 초과")
		} catch (e: PlaywrightException) {
			throw ApplyFailure(FailureKind.NETWORK_ERROR, "콘솔에 접근하지 못했다: ${e.javaClass.simpleName}")
		} finally {
			page.close()
		}
	}

	private fun login(page: Page, target: ConsoleTarget) {
		page.fill("input[name=username]", target.user)
		page.fill("input[name=password]", target.password)
		page.waitForNavigation { page.click("button[type=submit]") }
		if (onLoginPage(page)) throw ApplyFailure(FailureKind.AUTH_FAILED, "콘솔 로그인 실패")
	}

	private fun fillForm(page: Page, recipe: RecipeToApply) {
		page.fill("input[name=part_no]", recipe.partNo)
		page.fill("input[name=version]", recipe.version.toString())
		recipe.params.forEach { (field, value) -> page.fill("input[name=$field]", value) }
	}

	private fun save(page: Page): Int =
		page.waitForNavigation { page.click("button[type=submit]") }.status()

	/**
	 * 저장 후 폼을 **다시 열어** 읽는다. 같은 페이지의 화면 값이 아니라 새 요청의 응답을 읽는 것이 요점이다 —
	 * 불일치는 재시도해도 같은 값이 들어가므로 재시도하지 않는 실패다(결정 대기 2ⓒ).
	 */
	private fun verify(page: Page, target: ConsoleTarget, recipe: RecipeToApply) {
		page.navigate("${target.baseUrl}/recipe")
		if (onLoginPage(page)) throw ApplyFailure(FailureKind.SESSION_EXPIRED, "재조회 직전에 세션이 끊겼다")
		if (page.inputValue("input[name=part_no]") != recipe.partNo) {
			throw ApplyFailure(FailureKind.VERIFY_MISMATCH, "part_no 저장값이 다르다")
		}
		if (page.inputValue("input[name=version]") != recipe.version.toString()) {
			throw ApplyFailure(FailureKind.VERIFY_MISMATCH, "version 저장값이 다르다")
		}
		recipe.params.forEach { (field, expected) ->
			val actual = page.inputValue("input[name=$field]")
			val same = runCatching { BigDecimal(actual).compareTo(BigDecimal(expected)) == 0 }.getOrDefault(false)
			if (!same) throw ApplyFailure(FailureKind.VERIFY_MISMATCH, "$field 저장값이 다르다(기대 $expected, 실제 $actual)")
		}
	}

	private fun onLoginPage(page: Page) = page.url().endsWith("/login")

	private fun errorText(page: Page) = page.textContent(".error")?.trim().orEmpty()

	override fun close() {
		all.forEach { runCatching { it.playwright.close() } }
	}
}
