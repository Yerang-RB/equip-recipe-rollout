package com.rollout

import com.rollout.job.Backoff
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackoffTest {

	private val base = Duration.ofSeconds(2)
	private val max = Duration.ofSeconds(60)

	@Test
	fun doublesPerAttemptUpToTheCap() {
		assertEquals(Duration.ofSeconds(2), Backoff.of(1, base, max))
		assertEquals(Duration.ofSeconds(4), Backoff.of(2, base, max))
		assertEquals(Duration.ofSeconds(8), Backoff.of(3, base, max))
		assertEquals(max, Backoff.of(10, base, max))
		// 상한이 지수 폭주를 막는다(오버플로로 음수가 되지 않는다)
		assertEquals(max, Backoff.of(100, base, max))
		assertFailsWith<IllegalArgumentException> { Backoff.of(0, base, max) }
	}
}
