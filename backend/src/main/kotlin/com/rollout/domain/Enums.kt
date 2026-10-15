package com.rollout.domain

/** 설계 §8 고정 3역할. */
enum class Role { OPERATOR, APPROVER, ADMIN }

enum class EquipmentStatus { RUNNING, STOPPED, MAINTENANCE }

enum class RecipeStatus { DRAFT, ACTIVE, RETIRED }

enum class JobStatus {
	PENDING, RUNNING, SUCCEEDED, PARTIAL, FAILED;

	companion object {
		/** 집계는 task 상태만의 함수다(불변 조건 5) — NO_CHANGE도 task로는 SUCCEEDED다. */
		fun aggregate(taskStatuses: Collection<TaskStatus>): JobStatus {
			require(taskStatuses.isNotEmpty()) { "task 없는 잡은 만들어지지 않는다" }
			val unfinished = setOf(TaskStatus.PENDING, TaskStatus.CLAIMED)
			return when {
				taskStatuses.any { it in unfinished } -> RUNNING
				taskStatuses.all { it == TaskStatus.SUCCEEDED } -> SUCCEEDED
				taskStatuses.none { it == TaskStatus.SUCCEEDED } -> FAILED
				else -> PARTIAL
			}
		}
	}
}

/** CLAIMED가 곧 실행 중이다 — RUNNING은 두지 않는다(불변 조건 10). */
enum class TaskStatus { PENDING, CLAIMED, SUCCEEDED, FAILED, CANCELLED }

enum class HistoryResult { APPLIED, NO_CHANGE }
