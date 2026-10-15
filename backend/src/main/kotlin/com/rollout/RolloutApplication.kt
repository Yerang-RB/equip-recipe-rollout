package com.rollout

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import com.rollout.job.WorkerProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@SpringBootApplication
@EnableConfigurationProperties(WorkerProperties::class)
class RolloutApplication

/**
 * api·worker 한 jar, 역할은 `mode` 프로퍼티가 정한다(불변 조건 2). 프로퍼티가 없으면 플레이스홀더가
 * 풀리지 않아 기동이 실패하고, 값이 이상하면 여기서 실패한다 — 기본값을 두지 않는 것이 요점이다.
 */
@Configuration(proxyBeanMethods = false)
class ModeConfiguration(@Value("\${mode}") mode: String) {

	init {
		require(mode in setOf("api", "worker")) { "mode는 api 또는 worker여야 한다: $mode" }
	}

	/** 시각은 이 빈으로만 읽는다(불변 조건 5) — SQL now()를 쓰지 않는다. */
	@Bean
	fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

fun main(args: Array<String>) {
	runApplication<RolloutApplication>(*args)
}
