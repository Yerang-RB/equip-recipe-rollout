package com.rollout.worker

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * 워커는 애플리케이션 API를 매핑하지 않는다(불변 조건 2 · 결정 대기 3). HTTP는 운영이 보는 둘뿐이고
 * 나머지는 존재를 알리지 않는다(404) — 워커가 열어 둔 경로는 적을수록 좋다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["mode"], havingValue = "worker")
class WorkerConfiguration {

	@Bean
	fun workerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
		http
			.authorizeHttpRequests {
				it.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll().anyRequest().denyAll()
			}
			.exceptionHandling {
				it.authenticationEntryPoint { _, response, _ -> response.status = 404 }
					.accessDeniedHandler { _, response, _ -> response.status = 404 }
			}
			.build()

	/** api와 같은 이유로 빈 매니저를 둔다 — 없으면 기동 로그에 생성 비밀번호가 찍힌다. */
	@Bean
	fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()
}
