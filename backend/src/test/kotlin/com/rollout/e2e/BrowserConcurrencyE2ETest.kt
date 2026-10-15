package com.rollout.e2e

import com.rollout.worker.PlaywrightRecipeApplier
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals

/**
 * E2E 5(설계 §9): 동시 브라우저 세션이 상한을 **정확히** 채우고 넘지 않는다. 판정은 등호 둘이다 —
 * 워커가 센 peak(자기 신고)와 콘솔이 센 in-flight peak(독립 관측). 총 호출 수·소요 시간은 쓰지 않는다.
 *
 * 클레임 병렬도(6)가 상한(2)보다 크므로 세마포어가 실제 상한이다 — 씨앗 S5가 그 상한을 풀 크기로
 * 넓히면 두 등호가 함께 깨진다.
 */
// 하위 클래스의 @SpringBootTest는 상위의 것을 **대체한다**(누적이 아니다) — 부모의 값도 함께 적는다
@SpringBootTest(
	properties = [
		"mode=worker",
		"worker.backoff-base-seconds=0",
		"worker.browser-concurrency=2",
		"worker.claim-parallelism=6",
	],
)
class BrowserConcurrencyE2ETest @Autowired constructor(
	private val applier: PlaywrightRecipeApplier,
) : E2ESupport() {

	@Test
	fun peakConcurrentSessionsEqualsLimit() {
		val requester = user()
		val recipe = recipe(requester)
		val equipments = (1..6).map { equipment() }
		// 콘솔이 느려야 동시 점유가 겹친다(지연은 결정적 주입이지 판정 기준이 아니다)
		console.control.delayMs.set(300)

		assertEquals("SUCCEEDED", awaitJob(deploy(recipe, equipments.map { it.first }, requester)))

		assertEquals(2, applier.peakSessions(), "워커가 연 동시 세션의 최댓값")
		assertEquals(2, console.control.peakInFlight(), "콘솔이 동시에 처리한 요청의 최댓값(독립 관측)")
	}
}
