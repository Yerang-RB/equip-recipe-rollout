package com.rollout.job

/** 설계 §7 분류표의 행 이름. 표가 8행이라는 것도 계약이다(`FailureClassifierTest`). */
enum class FailureKind {
	TIMEOUT,
	SERVER_ERROR,
	SESSION_EXPIRED,
	NETWORK_ERROR,
	VALUE_REJECTED,
	EQUIPMENT_MAINTENANCE,
	AUTH_FAILED,
	VERIFY_MISMATCH,
}

/**
 * 설계 §7 실패 분류표. 분류를 틀리면 무한 재시도이거나 조기 포기다 — 표는 코드와 테스트 양쪽에 있고,
 * `when`이 전수라 행이 늘면 컴파일이 깨진다.
 */
object FailureClassifier {

	fun retryable(kind: FailureKind): Boolean = when (kind) {
		// 재시도 O: 설비·네트워크의 일시 상태
		FailureKind.TIMEOUT -> true
		FailureKind.SERVER_ERROR -> true
		FailureKind.SESSION_EXPIRED -> true
		FailureKind.NETWORK_ERROR -> true
		// 재시도 X: 다시 해도 같은 결과이거나, 사람이 고쳐야 하는 것
		FailureKind.VALUE_REJECTED -> false
		FailureKind.EQUIPMENT_MAINTENANCE -> false
		FailureKind.AUTH_FAILED -> false
		FailureKind.VERIFY_MISMATCH -> false
	}
}
