package com.rollout

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.web.multipart.MultipartResolver
import kotlin.test.assertEquals

/**
 * 설정으로 표현된 방어의 **값** 핀(불변 조건 4·5·8·11). 문자열 등호라 삭제(null)와 반전 둘 다 붉어진다.
 * 동작은 따로 본다 — 미지 필드 400·413·멀티파트 400은 [ErrorHandlingTest], 쿠키 속성은 `AuthApiTest`,
 * 구조화 로깅은 `LoggingTest`, 동시성 상한은 `BrowserConcurrencyE2ETest`가 실제로 밟는다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class ConfigPinTest @Autowired constructor(
	private val env: Environment,
	private val context: ApplicationContext,
) {

	@Test
	fun defensiveSettingsArePinned() {
		assertEquals("127.0.0.1", env.getProperty("server.address"))
		assertEquals("false", env.getProperty("spring.servlet.multipart.enabled"))
		assertEquals("false", env.getProperty("spring.mvc.formcontent.filter.enabled"))
		assertEquals("true", env.getProperty("spring.jackson.deserialization.fail-on-unknown-properties"))
		assertEquals("logstash", env.getProperty("logging.structured.format.console"))
		assertEquals("3", env.getProperty("worker.browser-concurrency"))
		assertEquals("120", env.getProperty("worker.lease-seconds"))
		assertEquals("30", env.getProperty("worker.heartbeat-seconds"))
	}

	/** 값이 꺼져 있다는 것의 실체 — 멀티파트 파서 자체가 컨텍스트에 없다(프로퍼티만 되돌려도 여기서 붉어진다). */
	@Test
	fun noMultipartResolverIsRegistered() {
		assertEquals(emptyList(), context.getBeanNamesForType(MultipartResolver::class.java).toList())
	}
}
