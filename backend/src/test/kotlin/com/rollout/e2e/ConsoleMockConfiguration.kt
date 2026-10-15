package com.rollout.e2e

import com.equipconsole.ConsoleMockApplication
import com.equipconsole.MockControl
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean

const val CONSOLE_USER = "eq-operator"
const val CONSOLE_PASSWORD = "console-secret-1234"

/** 테스트가 콘솔을 만지는 창구: 주소와 결정적 주입(불변 조건 12 — HTTP가 아니라 빈으로 넣는다). */
class ConsoleMock(val baseUrl: String, val control: MockControl) {

	fun stationUrl(code: String) = "$baseUrl/$code"
}

/**
 * 목업 콘솔을 **같은 JVM 안에서 랜덤 포트로** 띄운다(불변 조건 12). backend 테스트 클래스패스에는
 * jdbc·security·flyway가 있어 목업 컨텍스트가 그것들을 자동설정하면 DataSource가 없어 기동이 깨진다 —
 * 목업이 쓰는 것은 웹 계층뿐이므로 그 자동설정만 끈다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ConsoleMockConfiguration {

	@Bean(destroyMethod = "close")
	fun consoleMockContext(): ConfigurableApplicationContext =
		SpringApplicationBuilder(ConsoleMockApplication::class.java)
			.properties(
				"server.port=0",
				"server.address=127.0.0.1",
				"MOCK_CONSOLE_USER=$CONSOLE_USER",
				"MOCK_CONSOLE_PASS=$CONSOLE_PASSWORD",
				// 테스트에서 확률 주입은 0이다 — 실패는 MockControl로 결정적으로 넣는다
				"MOCK_FAIL_RATE=0",
				"MOCK_DELAY_MS=0",
				"spring.autoconfigure.exclude=" + listOf(
					"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
					"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
					"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
					"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
					"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
					"org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
					"org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
				).joinToString(","),
			)
			.run()

	@Bean
	fun consoleMock(consoleMockContext: ConfigurableApplicationContext): ConsoleMock {
		val port = (consoleMockContext as WebServerApplicationContext).webServer!!.port
		return ConsoleMock("http://127.0.0.1:$port", consoleMockContext.getBean(MockControl::class.java))
	}
}
