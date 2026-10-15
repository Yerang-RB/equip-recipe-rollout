package com.rollout.job

import java.time.Duration

/** 결정적 지수 백오프(불변 조건 5): `base × 2^(attempt−1)`, 상한까지. 지터는 두지 않는다. */
object Backoff {

	fun of(attempt: Int, base: Duration, max: Duration): Duration {
		require(attempt >= 1) { "attempt는 1부터다: $attempt" }
		// 2^62초를 넘기면 Duration 곱셈이 넘치므로 지수를 상한 전에 자른다
		val steps = minOf(attempt - 1, 40)
		val raw = base.multipliedBy(1L shl steps)
		return if (raw > max) max else raw
	}
}
