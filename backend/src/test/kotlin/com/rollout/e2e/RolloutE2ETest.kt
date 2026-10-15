package com.rollout.e2e

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 크로미엄이 목업 콘솔을 조작하는 경로 전체(설계 §9 E2E 1~4). 판정은 상태·횟수·콘솔에 남은 값으로만
 * 한다 — 워커의 자기 신고(로그·메트릭)는 여기서 쓰지 않는다.
 */
class RolloutE2ETest : E2ESupport() {

	@Test
	fun allTwelveEquipmentsAppliedWithTwelveHistoryRows() {
		val requester = user()
		val recipe = recipe(requester)
		val equipments = (1..12).map { equipment() }

		val firstJob = deploy(recipe, equipments.map { it.first }, requester)
		assertEquals("SUCCEEDED", awaitJob(firstJob))
		// 소유권은 시도 단위다(리뷰 B6) — 클레임 스레드들이 id 하나를 나눠 쓰면 리스를 회수한 같은 프로세스의 다른 스레드와 원래 스레드를 DB가 못 가른다.
		assertEquals(
			12,
			jdbc.queryForObject("select count(distinct claimed_by)::int from rollout_task where job_id = ?", Int::class.java, firstJob),
			"시도마다 소유자 id가 다르다",
		)

		equipments.forEach { (id, code) ->
			assertEquals(1, historyCount(id, "APPLIED"), "설비 $code 의 적용 이력")
			val saved = console.control.savedRecipe(code)
			assertEquals(recipe.partNo, saved["part_no"], "콘솔에 실제로 저장된 품번")
			assertEquals("1", saved["version"])
			STANDARD_PARAMS.forEach { (field, value) -> assertEquals(value, saved[field], "콘솔에 실제로 저장된 $field") }
		}

		// 멱등 2층: 같은 레시피를 다시 배포하면 브라우저를 열지 않는다(로그인 횟수가 늘지 않는다)
		val loginsBefore = equipments.sumOf { console.control.loginCount(it.second) }
		assertTrue(loginsBefore >= 12, "12대에 적어도 한 번씩은 로그인했다")

		assertEquals("SUCCEEDED", awaitJob(deploy(recipe, equipments.map { it.first }, requester)))

		equipments.forEach { (id, code) ->
			assertEquals(1, historyCount(id, "NO_CHANGE"), "설비 $code 의 재배포 이력")
			assertEquals(1, historyCount(id, "APPLIED"), "재배포가 적용 이력을 늘리지 않는다")
		}
		assertEquals(loginsBefore, equipments.sumOf { console.control.loginCount(it.second) }, "재배포는 로그인하지 않는다")
	}

	@Test
	fun retriesAfterInjected500() {
		val requester = user()
		val recipe = recipe(requester)
		val (equipmentId, _) = equipment()

		console.control.failNextSaves(1)

		assertEquals("SUCCEEDED", awaitJob(deploy(recipe, listOf(equipmentId), requester)))
		val task = tasks(jobIdOf(equipmentId)).single()
		assertEquals(2, task["attempt"], "첫 시도는 500으로 죽고 두 번째가 성공한다")
		assertEquals(1, historyCount(equipmentId, "APPLIED"), "재시도가 이력을 오염시키지 않는다")
	}

	@Test
	fun reLoginsAfterSessionExpiry() {
		val requester = user()
		val recipe = recipe(requester)
		val (equipmentId, code) = equipment()

		// 두 번째 인증 요청(= 저장)에서 세션이 사라진다 → 저장 응답이 로그인 페이지
		console.control.expireSessionsAfterRequests(2)

		assertEquals("SUCCEEDED", awaitJob(deploy(recipe, listOf(equipmentId), requester)))
		assertEquals(2, console.control.loginCount(code), "세션이 끊기면 재로그인하고 같은 시도 안에서 저장을 마친다")
		assertEquals(1, tasks(jobIdOf(equipmentId)).single()["attempt"], "세션 만료는 task 재시도 없이 복구된다")
		assertEquals(1, historyCount(equipmentId, "APPLIED"))
	}

	@Test
	fun rejectedValueIsNotRetried() {
		val requester = user()
		val recipe = recipe(requester, STANDARD_PARAMS + ("cooling_sec" to "999"))
		val (equipmentId, _) = equipment()

		assertEquals("FAILED", awaitJob(deploy(recipe, listOf(equipmentId), requester)))
		val task = tasks(jobIdOf(equipmentId)).single()
		assertEquals(1, task["attempt"], "값 거부는 다시 해도 같은 결과다 — 재시도하지 않는다")
		assertEquals(0, historyCount(equipmentId, "APPLIED"))
		val error = task["last_error"] as String
		assertTrue(error.startsWith("VALUE_REJECTED:"), "분류가 붙는다: $error")
		assertTrue(error.contains("cooling_sec") && error.contains("1..120"), "콘솔 문구가 남는다: $error")
	}

	private fun jobIdOf(equipmentId: Long): Long =
		jdbc.queryForObject("select job_id from rollout_task where equipment_id = ?", Long::class.java, equipmentId)!!
}
