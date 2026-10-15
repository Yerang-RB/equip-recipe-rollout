package com.rollout.job

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class AppliedRecipe(val partNo: String, val version: Int)

@Repository
class HistoryRepository(private val jdbc: JdbcClient) {

	/** 멱등 2층(설계 §7): 이미 같은 품번·버전이 적용된 설비면 브라우저를 열지 않고 NO_CHANGE로 끝낸다. */
	fun latestApplied(equipmentId: Long): AppliedRecipe? =
		jdbc.sql(
			"""
			select part_no, version from application_history
			where equipment_id = :equipment and result = 'APPLIED'
			order by applied_at desc, id desc
			limit 1
			""".trimIndent(),
		)
			.param("equipment", equipmentId)
			.query { rs, _ -> AppliedRecipe(rs.getString("part_no"), rs.getInt("version")) }
			.optional()
			.orElse(null)
}
