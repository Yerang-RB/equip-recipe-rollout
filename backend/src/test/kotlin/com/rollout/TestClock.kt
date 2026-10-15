package com.rollout

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 앞으로만 움직이는 테스트 클록(불변 조건 5). 되돌리는 메서드는 일부러 없다 — 리스·백오프 판정이
 * 테스트 순서와 무관하게 단조로 유지된다.
 */
class MutableClock(private var instant: Instant) : Clock() {

	@Synchronized
	fun advance(by: Duration) {
		require(!by.isNegative) { "클록은 앞으로만 움직인다: $by" }
		instant = instant.plus(by)
	}

	/** 운영 클록과 같이 마이크로초로 자른다(timestamptz 정밀도). */
	@Synchronized
	override fun instant(): Instant = instant.truncatedTo(ChronoUnit.MICROS)

	override fun getZone(): ZoneId = ZONE

	override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException("Asia/Seoul 고정")

	companion object {
		val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
	}
}

@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

	@Bean
	@Primary
	fun mutableClock(): MutableClock = MutableClock(Instant.now())
}
