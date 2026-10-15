package com.rollout.e2e

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「저장했다」는 콘솔의 자기 신고다. 저장 응답이 200이어도 값이 다르게 들어갈 수 있고, 그것을 잡는 것은
 * 저장 후 폼을 다시 열어 읽는 대조뿐이다(설계 §5 3번). 씨앗 S2가 죽이는 테스트가 이것이다.
 */
class ApplyVerificationTest : E2ESupport() {

	@Test
	fun mismatchAfterSaveFailsTheTask() {
		val requester = user()
		val recipe = recipe(requester)
		val (equipmentId, code) = equipment()

		// 저장은 200인데 cooling_sec만 +1로 들어간다
		console.control.corruptNextSave()

		assertEquals("FAILED", awaitJob(deploy(recipe, listOf(equipmentId), requester)))

		val task = tasks(jobIdOf(equipmentId)).single()
		val error = task["last_error"] as String
		assertTrue(error.startsWith("VERIFY_MISMATCH:"), "불일치로 분류된다: $error")
		assertTrue(error.contains("cooling_sec"), "어느 필드가 어긋났는지 남는다: $error")
		assertEquals(1, task["attempt"], "다시 해도 같은 값이 들어간다 — 재시도하지 않는다")
		assertEquals(0, historyCount(equipmentId, "APPLIED"), "대조에 실패한 적용은 이력이 되지 않는다")
		assertEquals(
			"13",
			console.control.savedRecipe(code)["cooling_sec"],
			"양의 앵커: 콘솔에는 실제로 어긋난 값이 저장돼 있다(저장 자체는 성공했다)",
		)
	}

	private fun jobIdOf(equipmentId: Long): Long =
		jdbc.queryForObject("select job_id from rollout_task where equipment_id = ?", Long::class.java, equipmentId)!!
}
