package com.rollout.api

import com.rollout.domain.RecipeStatus
import com.rollout.job.toDb
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Clock

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class RecipeController(
	private val jdbc: JdbcClient,
	private val clock: Clock,
	private val json: ObjectMapper,
) {

	data class RecipeRequest(val partNo: String, val version: Int, val params: Map<String, String>) {
		init {
			requireText(partNo, 50)
			require(version >= 1)
			// 키는 워커가 콘솔 폼의 CSS 선택자(`input[name=…]`)에 그대로 넣는다 — 문자셋을 여기서 막는다(리뷰 B9).
			require(params.keys.all(PARAM_KEY::matches))
		}
	}

	private data class RecipeState(val status: RecipeStatus, val createdBy: Long)

	@GetMapping("/api/recipes")
	fun list(@RequestAttribute(ACTOR) actor: Actor): List<Map<String, Any?>> {
		Policy.check(actor, Action.VIEW)
		return jdbc.sql("$SELECT order by id desc").query(::row).list()
	}

	@GetMapping("/api/recipes/{id}")
	fun get(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long): Map<String, Any?> {
		Policy.check(actor, Action.VIEW)
		return one(id)
	}

	@PostMapping("/api/recipes")
	fun create(@RequestAttribute(ACTOR) actor: Actor, @RequestBody body: RecipeRequest): ResponseEntity<Map<String, Any?>> {
		Policy.check(actor, Action.RECIPE_DRAFT)
		val id = jdbc.sql(
			"""
			insert into recipe(part_no, version, params, status, created_by, created_at)
			values (:partNo, :version, cast(:params as jsonb), 'DRAFT', :actor, :now) returning id
			""".trimIndent(),
		)
			.param("partNo", body.partNo)
			.param("version", body.version)
			.param("params", json.writeValueAsString(body.params))
			.param("actor", actor.id)
			.param("now", clock.instant().toDb())
			.query(Long::class.java)
			.single()
		return ResponseEntity.status(201).body(one(id))
	}

	/**
	 * 초안만 고칠 수 있다 — 승인·폐기된 레시피는 이력의 근거라 바뀌면 안 된다. 고친 사람이 새 작성자가 된다:
	 * 남의 초안을 자기 내용으로 덮어쓴 뒤 스스로 승인하면 4-eyes가 무력해진다(리뷰 B1).
	 */
	@PutMapping("/api/recipes/{id}")
	fun update(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long, @RequestBody body: RecipeRequest): Map<String, Any?> {
		Policy.check(actor, Action.RECIPE_DRAFT)
		if (state(id).status != RecipeStatus.DRAFT) conflict("INVALID_TRANSITION", "초안만 수정할 수 있다")
		// 전이 조건을 UPDATE에 싣는다 — 읽고 검사한 뒤 조건 없이 쓰면 두 요청이 겹칠 때 둘 다 통과한다(메타 리뷰 주의 1).
		val updated = jdbc.sql(
			"update recipe set part_no = :partNo, version = :version, params = cast(:params as jsonb), created_by = :actor " +
				"where id = :id and status = 'DRAFT'",
		)
			.param("actor", actor.id)
			.param("partNo", body.partNo)
			.param("version", body.version)
			.param("params", json.writeValueAsString(body.params))
			.param("id", id)
			.update()
		if (updated == 0) conflict("INVALID_TRANSITION", "초안만 수정할 수 있다")
		return one(id)
	}

	@PostMapping("/api/recipes/{id}/approve")
	fun approve(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long): Map<String, Any?> {
		val state = state(id)
		// 4-eyes 조건은 작성자 id를 넘겨 Policy 안에서 판정한다(불변 조건 3).
		Policy.check(actor, Action.RECIPE_APPROVE_RETIRE, state.createdBy)
		if (state.status != RecipeStatus.DRAFT) conflict("INVALID_TRANSITION", "초안만 승인할 수 있다")
		// 4-eyes와 상태를 **UPDATE 조건으로** 다시 건다 — 검사와 갱신 사이에 남이 승인하거나 작성자를
		// 자기로 바꾸면 검사만으로는 막지 못한다(메타 리뷰 주의 1 — 리뷰 B1이 경합으로 다시 열리는 자리).
		val approved = jdbc.sql(
			"update recipe set status = 'ACTIVE', approved_by = :actor where id = :id and status = 'DRAFT' and created_by <> :actor",
		)
			.param("actor", actor.id)
			.param("id", id)
			.update()
		if (approved == 0) {
			if (state(id).createdBy == actor.id) forbidden("SELF_APPROVAL_FORBIDDEN")
			conflict("INVALID_TRANSITION", "초안만 승인할 수 있다")
		}
		return one(id)
	}

	@PostMapping("/api/recipes/{id}/retire")
	fun retire(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long): Map<String, Any?> {
		Policy.check(actor, Action.RECIPE_APPROVE_RETIRE)
		if (state(id).status != RecipeStatus.ACTIVE) conflict("INVALID_TRANSITION", "활성 레시피만 폐기할 수 있다")
		val retired = jdbc.sql("update recipe set status = 'RETIRED' where id = :id and status = 'ACTIVE'").param("id", id).update()
		if (retired == 0) conflict("INVALID_TRANSITION", "활성 레시피만 폐기할 수 있다")
		return one(id)
	}

	private fun state(id: Long): RecipeState =
		jdbc.sql("select status, created_by from recipe where id = :id")
			.param("id", id)
			.query { rs, _ -> RecipeState(RecipeStatus.valueOf(rs.getString("status")), rs.getLong("created_by")) }
			.optional()
			.orElseGet { notFound() }

	private fun one(id: Long): Map<String, Any?> = jdbc.sql("$SELECT where id = :id").param("id", id).query(::row).single()

	private fun row(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): Map<String, Any?> = mapOf(
		"id" to rs.getLong("id"),
		"partNo" to rs.getString("part_no"),
		"version" to rs.getInt("version"),
		"status" to rs.getString("status"),
		"params" to json.readValue(rs.getString("params"), Map::class.java),
		"createdBy" to rs.getLong("created_by"),
		"approvedBy" to rs.getObject("approved_by"),
		"createdAt" to rs.getString("created_at"),
	)

	private companion object {
		val PARAM_KEY = Regex("^[a-z][a-z0-9_]{0,49}$")
		const val SELECT = "select id, part_no, version, status, cast(params as text) as params, created_by, approved_by, cast(created_at as text) as created_at from recipe"
	}
}
