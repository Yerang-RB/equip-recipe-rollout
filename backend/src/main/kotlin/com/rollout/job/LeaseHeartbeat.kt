package com.rollout.job

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 실행 중인 task의 리스만 연장한다(불변 조건 5). 자기가 쥔 행이 아니면 아무것도 하지 않는다 —
 * 리스를 뺏긴 워커가 남의 리스를 되살리면 회수(=크래시 복구)가 무력해진다.
 */
@Component
class LeaseHeartbeat(
	private val jdbc: JdbcClient,
	private val clock: Clock,
	private val props: WorkerProperties,
) {

	fun tick(taskId: Long, workerId: String): Boolean {
		val now = clock.instant()
		return jdbc.sql(
			"""
			update rollout_task
			set lease_expires_at = :lease, updated_at = :now
			where id = :id and status = 'CLAIMED' and claimed_by = :worker
			""".trimIndent(),
		)
			.param("lease", now.plusSeconds(props.leaseSeconds).toDb())
			.param("now", now.toDb())
			.param("id", taskId)
			.param("worker", workerId)
			.update() == 1
	}
}
