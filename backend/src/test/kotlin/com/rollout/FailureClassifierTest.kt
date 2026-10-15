package com.rollout

import com.rollout.job.FailureClassifier
import com.rollout.job.FailureKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 설계 §7의 표를 테스트 쪽에도 **따로** 적는다 — 운영 코드의 `when`을 그대로 읽어 오면 표가 바뀐 것을
 * 알아채지 못한다. 행 수 등호가 「행이 늘었는데 분류를 안 정했다」를 잡는다.
 */
class FailureClassifierTest {

	private val table = mapOf(
		FailureKind.TIMEOUT to true,
		FailureKind.SERVER_ERROR to true,
		FailureKind.SESSION_EXPIRED to true,
		FailureKind.NETWORK_ERROR to true,
		FailureKind.VALUE_REJECTED to false,
		FailureKind.EQUIPMENT_MAINTENANCE to false,
		FailureKind.AUTH_FAILED to false,
		FailureKind.VERIFY_MISMATCH to false,
	)

	@Test
	fun everyTableRowIsClassified() {
		assertEquals(8, table.size, "설계 §7 표는 8행이다")
		assertEquals(FailureKind.entries.size, table.size, "분류 밖의 실패 종류가 없다")
		table.forEach { (kind, retryable) -> assertEquals(retryable, FailureClassifier.retryable(kind), kind.name) }
	}
}
