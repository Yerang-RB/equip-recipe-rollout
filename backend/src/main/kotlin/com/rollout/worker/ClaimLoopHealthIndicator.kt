package com.rollout.worker

import com.rollout.job.WorkerProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * 워커의 헬스는 「클레임 루프가 살아 있는가」다(설계 §10). 프로세스가 떠 있는 것과 일이 돌고 있는 것은
 * 다르다 — 마지막 클레임 시도가 `worker.loop-stale-seconds`보다 오래됐으면 DOWN이다(클록 빈 기준).
 */
@Component
@ConditionalOnProperty(name = ["mode"], havingValue = "worker")
class ClaimLoopHealthIndicator(
	private val loop: ClaimLoop,
	private val props: WorkerProperties,
	private val clock: Clock,
) : HealthIndicator {

	override fun health(): Health {
		val since = Duration.between(loop.lastIterationAt(), clock.instant())
		val detail = Health.Builder().withDetail("secondsSinceLastIteration", since.seconds)
		return if (since.seconds > props.loopStaleSeconds) detail.down().build() else detail.up().build()
	}
}
