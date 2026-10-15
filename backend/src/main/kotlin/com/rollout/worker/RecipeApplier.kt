package com.rollout.worker

import com.rollout.job.FailureKind

/** 설비 콘솔 한 대(설비별 자격증명은 `equipment` 행에 있다 — 응답·로그·감사에는 절대 싣지 않는다). */
data class ConsoleTarget(val equipmentId: Long, val baseUrl: String, val user: String, val password: String)

data class RecipeToApply(val partNo: String, val version: Int, val params: Map<String, String>)

/** 분류표(불변 조건 7)의 행을 달고 죽는다 — 재시도 여부는 `FailureClassifier`가 이 `kind`로 정한다. */
class ApplyFailure(val kind: FailureKind, message: String) : RuntimeException(message)

/**
 * 설계가 명시적으로 열어 둔 유일한 인터페이스(§12) — 설비 콘솔이 API를 제공하게 되면 이 구현만 바뀐다.
 */
interface RecipeApplier {

	fun apply(target: ConsoleTarget, recipe: RecipeToApply)
}
