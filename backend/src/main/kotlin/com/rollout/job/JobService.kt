package com.rollout.job

import com.rollout.domain.JobStatus
import com.rollout.domain.TaskStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 같은 `Idempotency-Key`면 `created=false`로 기존 잡을 돌려준다(멱등 1층). */
data class JobCreation(val jobId: Long, val created: Boolean)

@Service
class JobService(
	private val jdbc: JdbcClient,
	private val clock: Clock,
	private val notifier: ProgressNotifier,
) {

	@Transactional
	fun create(recipeId: Long, equipmentIds: List<Long>, requestedBy: Long, idempotencyKey: String): JobCreation {
		require(equipmentIds.isNotEmpty()) { "설비가 없는 잡은 만들지 않는다" }
		val now = clock.instant().toDb()
		// on conflict가 동시 요청까지 막는다 — 먼저 읽고 없으면 넣는 모양은 두 요청이 같은 창에서 겹친다
		val inserted = jdbc.sql(
			"""
			insert into rollout_job(recipe_id, status, idempotency_key, requested_by, created_at, updated_at)
			values (:recipe, 'PENDING', :key, :requester, :now, :now)
			on conflict (idempotency_key) do nothing
			returning id
			""".trimIndent(),
		)
			.param("recipe", recipeId)
			.param("key", idempotencyKey)
			.param("requester", requestedBy)
			.param("now", now)
			.query(Long::class.java)
			.optional()
			.orElse(null)

		if (inserted == null) {
			val existing = jdbc.sql("select id from rollout_job where idempotency_key = :key")
				.param("key", idempotencyKey)
				.query(Long::class.java)
				.single()
			return JobCreation(existing, created = false)
		}

		equipmentIds.distinct().forEach { equipmentId ->
			jdbc.sql(
				"""
				insert into rollout_task(job_id, equipment_id, status, attempt, created_at, updated_at)
				values (:job, :equipment, 'PENDING', 0, :now, :now)
				""".trimIndent(),
			)
				.param("job", inserted)
				.param("equipment", equipmentId)
				.param("now", now)
				.update()
		}
		return JobCreation(inserted, created = true)
	}

	/**
	 * 아직 시작하지 않은 task만 취소한다 — 실행 중(CLAIMED)인 것은 끝까지 간다(불변 조건 5). 다만 그 시도가
	 * 재시도 가능 실패로 끝나도 다시 돌지 않도록 표시를 남긴다(리뷰 B2, `TaskOutcomeService.fail`이 읽는다).
	 */
	@Transactional
	fun cancel(jobId: Long): Int {
		lockJob(jobId)
		val now = clock.instant().toDb()
		// ponytail: 취소 표시를 last_error 문구로 싣는다 — 스키마의 추가 열은 설계가 막았다(불변 조건 1). 잡 단위 취소 열이 생기면 그리로 옮긴다.
		jdbc.sql("update rollout_task set last_error = :note, updated_at = :now where job_id = :job and status = 'CLAIMED'")
			.param("note", CANCEL_NOTE)
			.param("now", now)
			.param("job", jobId)
			.update()
		// 리스가 만료된 CLAIMED는 워커가 죽어 **실행 중이 아니다** — 표시만 달아 두면 다른 워커가 회수해
		// 취소된 잡의 레시피를 설비에 쓴다(메타 리뷰 주의 5). PENDING과 같이 닫는다.
		val changed = jdbc.sql(
			"""
			update rollout_task set status = 'CANCELLED', claimed_by = null, lease_expires_at = null, updated_at = :now
			where job_id = :job and (status = 'PENDING' or (status = 'CLAIMED' and lease_expires_at < :lease))
			""".trimIndent(),
		)
			.param("now", now)
			.param("lease", now)
			.param("job", jobId)
			.update()
		refreshStatus(jobId)
		notifier.afterCommit(jobId)
		return changed
	}

	/** 실패·취소된 task를 처음 상태로 되돌린다(attempt 0, 백오프 해제). */
	@Transactional
	fun retry(jobId: Long): Int {
		lockJob(jobId)
		val changed = jdbc.sql(
			"""
			update rollout_task
			set status = 'PENDING', attempt = 0, next_attempt_at = null, last_error = null,
			    claimed_by = null, lease_expires_at = null, updated_at = :now
			where job_id = :job and status in ('FAILED', 'CANCELLED')
			""".trimIndent(),
		)
			.param("now", clock.instant().toDb())
			.param("job", jobId)
			.update()
		refreshStatus(jobId)
		notifier.afterCommit(jobId)
		return changed
	}

	/**
	 * 집계 전에 잡 행을 잠근다. 두 워커가 같은 잡의 마지막 두 task를 동시에 끝내면, 잠금 없이는 서로의
	 * 미커밋 결과를 못 봐 둘 다 `RUNNING`으로 덮어쓴다(잃어버린 갱신).
	 */
	fun lockJob(jobId: Long) {
		jdbc.sql("select id from rollout_job where id = :id for update").param("id", jobId).query(Long::class.java).optional()
	}

	companion object {
		const val CANCEL_NOTE = "CANCEL_REQUESTED: 잡이 취소됐다 — 이번 시도로 끝낸다"
	}

	fun refreshStatus(jobId: Long) {
		val statuses = jdbc.sql("select status from rollout_task where job_id = :job")
			.param("job", jobId)
			.query { rs, _ -> TaskStatus.valueOf(rs.getString("status")) }
			.list()
		jdbc.sql("update rollout_job set status = :status, updated_at = :now where id = :job")
			.param("status", JobStatus.aggregate(statuses).name)
			.param("now", clock.instant().toDb())
			.param("job", jobId)
			.update()
	}
}
