package com.rollout

import com.microsoft.playwright.Playwright
import com.rollout.worker.ClaimLoop
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals

/**
 * 불변 조건 2: api 모드는 브라우저를 만들지 않고 워커 빈도 뜨지 않는다. Playwright 빈이 api 컨텍스트에
 * 섞이면 api 프로세스가 크로미엄을 띄우게 된다 — 모드 분기가 죽었다는 신호다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class ApiModeTest @Autowired constructor(private val context: ApplicationContext) {

	@Test
	fun apiModeHasNoPlaywrightBean() {
		assertEquals(emptyList(), context.getBeanNamesForType(Playwright::class.java).toList())
		assertEquals(emptyList(), context.getBeanNamesForType(ClaimLoop::class.java).toList())
		// 양의 앵커: 같은 컨텍스트에 api 빈은 실제로 있다(모드 분기가 통째로 죽어도 위가 통과한다)
		assertEquals(1, context.getBeanNamesForType(com.rollout.api.HealthController::class.java).size)
	}
}
