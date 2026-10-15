package com.rollout.job

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock

data class ClaimedTask(val id: Long, val jobId: Long, val equipmentId: Long, val attempt: Int)

/**
 * DB 층 동시성(불변 조건 8): 한 task는 한 워커만. 고르기와 표시가 **문장 하나**라 고른 뒤 표시하기 전에
 * 끼어들 틈이 없고, `SKIP LOCKED`라 남이 쥔 행에서 막히지 않는다. 만료 리스를 집는 것이 곧 크래시 복구다.
 * 단 회수에도 상한이 있다(설계 §5 5번) — 없으면 브라우저를 못 띄우는 워커가 같은 task를 영원히 돌린다(메타 리뷰 치명 1).
 * 상한을 소진한 만료 리스는 `TaskOutcomeService.reapExhausted`가 FAILED로 닫는다.
 */
@Component
class TaskClaimer(
	private val jdbc: JdbcClient,
	private val clock: Clock,
	private val props: WorkerProperties,
	meters: MeterRegistry,
) {

	// prometheus 노출 이름은 lease_expired_recovered_total이다(마이크로미터가 _total을 붙인다)
	private val leaseRecovered = meters.counter("lease_expired_recovered")

	fun claim(workerId: String): ClaimedTask? {
		val now = clock.instant()
		val claimed = jdbc.sql(
			"""
			with picked as (
			    select id, status as prev_status
			    from rollout_task
			    where (status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= :now))
			       or (status = 'CLAIMED' and lease_expires_at < :now and attempt < :max)
			    order by id
			    for update skip locked
			    limit 1
			)
			update rollout_task t
			set status = 'CLAIMED',
			    claimed_by = :worker,
			    attempt = t.attempt + 1,
			    lease_expires_at = :lease,
			    next_attempt_at = null,
			    updated_at = :now
			from picked
			where t.id = picked.id
			returning t.id, t.job_id, t.equipment_id, t.attempt, picked.prev_status
			""".trimIndent(),
		)
			.param("now", now.toDb())
			.param("max", props.maxAttempts)
			.param("worker", workerId)
			.param("lease", now.plusSeconds(props.leaseSeconds).toDb())
			.query { rs, _ ->
				ClaimedTask(rs.getLong("id"), rs.getLong("job_id"), rs.getLong("equipment_id"), rs.getInt("attempt")) to
					rs.getString("prev_status")
			}
			.optional()
			.orElse(null) ?: return null

		if (claimed.second == "CLAIMED") leaseRecovered.increment()
		return claimed.first
	}
}
