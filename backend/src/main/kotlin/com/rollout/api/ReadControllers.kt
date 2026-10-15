package com.rollout.api

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class HistoryController(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	@GetMapping("/api/history")
	fun list(@RequestAttribute(ACTOR) actor: Actor, @RequestParam(required = false) equipmentId: Long?): List<Map<String, Any?>> {
		Policy.check(actor, Action.VIEW)
		return jdbc.sql(
			"""
			select h.id, h.equipment_id, e.code, h.part_no, h.version, h.result,
			       cast(h.applied_at as text) as applied_at, h.applied_by, cast(h.params_snapshot as text) as params
			from application_history h join equipment e on e.id = h.equipment_id
			where (cast(:equipment as bigint) is null or h.equipment_id = cast(:equipment as bigint))
			order by h.applied_at desc, h.id desc
			""".trimIndent(),
		)
			.param("equipment", equipmentId)
			.query { rs, _ ->
				mapOf(
					"id" to rs.getLong("id"),
					"equipmentId" to rs.getLong("equipment_id"),
					"equipmentCode" to rs.getString("code"),
					"partNo" to rs.getString("part_no"),
					"version" to rs.getInt("version"),
					"result" to rs.getString("result"),
					"appliedAt" to rs.getString("applied_at"),
					"appliedBy" to rs.getLong("applied_by"),
					"params" to json.readValue(rs.getString("params"), Map::class.java),
				)
			}
			.list()
	}
}

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class AuditController(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	@GetMapping("/api/audit")
	fun list(@RequestAttribute(ACTOR) actor: Actor): List<Map<String, Any?>> {
		Policy.check(actor, Action.AUDIT_VIEW)
		return jdbc.sql(
			"""
			select id, cast(at as text) as at, actor_id, actor_name, action, target_type, target_id, result,
			       cast(detail as text) as detail
			from audit_log order by id desc limit 500
			""".trimIndent(),
		)
			.query { rs, _ ->
				mapOf(
					"id" to rs.getLong("id"),
					"at" to rs.getString("at"),
					"actorId" to rs.getObject("actor_id"),
					"actorName" to rs.getString("actor_name"),
					"action" to rs.getString("action"),
					"targetType" to rs.getString("target_type"),
					"targetId" to rs.getObject("target_id"),
					"result" to rs.getString("result"),
					"detail" to json.readValue(rs.getString("detail"), Map::class.java),
				)
			}
			.list()
	}
}
