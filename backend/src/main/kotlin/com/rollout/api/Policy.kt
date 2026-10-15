package com.rollout.api

import com.rollout.domain.Role

/** 설계 §8 매트릭스의 행 — 행위 7개. */
enum class Action { VIEW, RECIPE_DRAFT, RECIPE_APPROVE_RETIRE, JOB_CREATE, JOB_CANCEL_RETRY, AUDIT_VIEW, USER_EQUIPMENT_MANAGE }

/**
 * 정책 검사는 이 한 곳에서만 한다(불변 조건 3). 표는 설계 §8 그대로의 리터럴이고,
 * 조건이 붙는 칸은 둘뿐이다 — 잡 취소·재시도의 「OPERATOR는 자기 잡만」과 승인의 4-eyes.
 */
object Policy {

	private val ALLOWED: Map<Action, Set<Role>> = mapOf(
		Action.VIEW to setOf(Role.OPERATOR, Role.APPROVER, Role.ADMIN),
		Action.RECIPE_DRAFT to setOf(Role.OPERATOR, Role.APPROVER),
		// ADMIN은 승인·폐기를 하지 못한다 — 계정을 쥔 사람이 품질 승인까지 하면 직무 분리가 깨진다.
		Action.RECIPE_APPROVE_RETIRE to setOf(Role.APPROVER),
		Action.JOB_CREATE to setOf(Role.OPERATOR, Role.APPROVER),
		Action.JOB_CANCEL_RETRY to setOf(Role.OPERATOR, Role.APPROVER, Role.ADMIN),
		Action.AUDIT_VIEW to setOf(Role.APPROVER, Role.ADMIN),
		Action.USER_EQUIPMENT_MANAGE to setOf(Role.ADMIN),
	)

	/**
	 * @param ownerId 대상의 소유자 — 잡은 요청자(`requested_by`), 승인은 레시피 작성자(`created_by`).
	 *                조건이 없는 행위는 null을 준다.
	 */
	fun check(actor: Actor, action: Action, ownerId: Long? = null) {
		if (actor.role !in ALLOWED.getValue(action)) forbidden()
		when (action) {
			Action.JOB_CANCEL_RETRY -> if (actor.role == Role.OPERATOR && ownerId != actor.id) forbidden()
			Action.RECIPE_APPROVE_RETIRE -> if (ownerId != null && ownerId == actor.id) forbidden("SELF_APPROVAL_FORBIDDEN")
			else -> {}
		}
	}
}
