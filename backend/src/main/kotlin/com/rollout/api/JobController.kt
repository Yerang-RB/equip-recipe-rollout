package com.rollout.api

import com.rollout.job.JobService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class JobController(private val jdbc: JdbcClient, private val jobs: JobService) {

	data class JobRequest(val recipeId: Long, val equipmentIds: List<Long>)

	@GetMapping("/api/jobs")
	fun list(@RequestAttribute(ACTOR) actor: Actor): List<Map<String, Any?>> {
		Policy.check(actor, Action.VIEW)
		return jdbc.sql("$SELECT order by j.id desc").query(::jobRow).list()
	}

	@GetMapping("/api/jobs/{id}")
	fun get(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long): Map<String, Any?> {
		Policy.check(actor, Action.VIEW)
		return detail(id)
	}

	/**
	 * `Idempotency-Key`는 필수다(불변 조건 5) — 같은 키의 재요청은 새 잡을 만들지 않고 200으로 기존 잡을 돌려준다.
	 * ACTIVE가 아닌 레시피로는 만들지 않는다(승인 안 된 조건이 설비로 나가는 것을 여기서 막는다).
	 */
	@PostMapping("/api/jobs")
	fun create(
		@RequestAttribute(ACTOR) actor: Actor,
		@RequestHeader("Idempotency-Key") key: String,
		@RequestBody body: JobRequest,
	): ResponseEntity<Map<String, Any?>> {
		Policy.check(actor, Action.JOB_CREATE)
		if (key.isEmpty() || key.length > 100) invalid("Idempotency-Key 길이가 올바르지 않다")
		val status = jdbc.sql("select status from recipe where id = :id")
			.param("id", body.recipeId)
			.query(String::class.java)
			.optional()
			.orElseGet { notFound() }
		if (status != "ACTIVE") conflict("RECIPE_NOT_ACTIVE", "활성 레시피로만 배포할 수 있다")
		// MAINTENANCE 설비는 요청에 넣을 수 없다(결정 대기 2ⓖ) — 실행 시점에 바뀐 설비는 워커가 재시도 X 실패로 닫는다.
		if (body.equipmentIds.isEmpty()) invalid("설비를 하나 이상 고른다")
		val maintenance = jdbc.sql("select count(*) from equipment where id in (:ids) and status = 'MAINTENANCE'")
			.param("ids", body.equipmentIds)
			.query(Int::class.java)
			.single()
		if (maintenance > 0) invalid("MAINTENANCE 설비는 배포 대상에 넣을 수 없다")
		val created = jobs.create(body.recipeId, body.equipmentIds, actor.id, key)
		return ResponseEntity.status(if (created.created) 201 else 200).body(detail(created.jobId))
	}

	@PostMapping("/api/jobs/{id}/cancel")
	fun cancel(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long): Map<String, Any?> {
		Policy.check(actor, Action.JOB_CANCEL_RETRY, requester(id))
		// 끝난 잡의 취소는 바꾸는 것이 없는데 200으로 통과해 감사에 「취소함」만 남았다(2026-09-17 데모 실사용 2건).
		if (jobStatus(id) in FINISHED) conflict("JOB_ALREADY_FINISHED", "이미 끝난 잡은 취소할 수 없다")
		jobs.cancel(id)
		return detail(id)
	}

	@PostMapping("/api/jobs/{id}/retry")
	fun retry(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long): Map<String, Any?> {
		Policy.check(actor, Action.JOB_CANCEL_RETRY, requester(id))
		// 재시도는 명시적으로 새로 시작하는 행위다 — 생성과 같은 규칙을 건다(메타 리뷰 주의 4).
		val recipeStatus = jdbc.sql("select r.status from rollout_job j join recipe r on r.id = j.recipe_id where j.id = :id")
			.param("id", id)
			.query(String::class.java)
			.optional()
			.orElseGet { notFound() }
		if (recipeStatus != "ACTIVE") conflict("RECIPE_NOT_ACTIVE", "활성 레시피로만 재시도할 수 있다")
		jobs.retry(id)
		return detail(id)
	}

	/** OPERATOR의 「자기 잡만」 조건이 보는 소유자다(불변 조건 3). */
	private fun jobStatus(jobId: Long): String =
		jdbc.sql("select status from rollout_job where id = :id")
			.param("id", jobId)
			.query(String::class.java)
			.optional()
			.orElseGet { notFound() }

	private fun requester(jobId: Long): Long =
		jdbc.sql("select requested_by from rollout_job where id = :id")
			.param("id", jobId)
			.query(Long::class.java)
			.optional()
			.orElseGet { notFound() }

	private fun detail(id: Long): Map<String, Any?> {
		val job = jdbc.sql("$SELECT where j.id = :id").param("id", id).query(::jobRow).optional().orElseGet { notFound() }
		val tasks = jdbc.sql(
			"""
			select t.id, t.equipment_id, e.code, t.status, t.attempt, t.last_error
			from rollout_task t join equipment e on e.id = t.equipment_id
			where t.job_id = :id order by t.id
			""".trimIndent(),
		)
			.param("id", id)
			.query { rs, _ ->
				mapOf(
					"id" to rs.getLong("id"),
					"equipmentId" to rs.getLong("equipment_id"),
					"equipmentCode" to rs.getString("code"),
					"status" to rs.getString("status"),
					"attempt" to rs.getInt("attempt"),
					"lastError" to rs.getString("last_error"),
				)
			}
			.list()
		return job + ("tasks" to tasks)
	}

	private companion object {
		/** 집계가 더 바뀌지 않는 상태들(`JobStatus.aggregate` 기준). */
		val FINISHED = setOf("SUCCEEDED", "PARTIAL", "FAILED")

		const val SELECT = """
			select j.id, j.recipe_id, r.part_no, r.version, j.status, j.requested_by, cast(j.created_at as text) as created_at
			from rollout_job j join recipe r on r.id = j.recipe_id
		"""
	}
}

private fun jobRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): Map<String, Any?> = mapOf(
	"id" to rs.getLong("id"),
	"recipeId" to rs.getLong("recipe_id"),
	"partNo" to rs.getString("part_no"),
	"version" to rs.getInt("version"),
	"status" to rs.getString("status"),
	"requestedBy" to rs.getLong("requested_by"),
	"createdAt" to rs.getString("created_at"),
)
