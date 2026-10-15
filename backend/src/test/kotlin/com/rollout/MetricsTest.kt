package com.rollout

import com.rollout.e2e.E2ESupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 메트릭 4종(불변 조건 11)이 **워커의 실제 노출 본문**에 이름 그대로 있는지. 이름이 정본이다. */
class MetricsTest @Autowired constructor(private val mockMvc: MockMvc) : E2ESupport() {

	@Test
	fun fourRolloutMetricsAreExposedOnWorkerPrometheus() {
		val requester = user()
		val (equipmentId, _) = equipment()
		assertEquals("SUCCEEDED", awaitJob(deploy(recipe(requester), listOf(equipmentId), requester)))

		val body = mockMvc.perform(get("/actuator/prometheus")).andReturn().response.contentAsString

		listOf(
			"rollout_task_duration_seconds",
			"rollout_task_total",
			"browser_sessions_active",
			"lease_expired_recovered_total",
		).forEach { assertTrue(it in body, "노출 본문에 $it 이 있다") }

		val succeeded = Regex("""rollout_task_total\{[^}]*result="SUCCEEDED"[^}]*}\s+([0-9.E]+)""").find(body)
		assertNotNull(succeeded, "성공 결과 태그가 붙은 계수: $body")
		assertTrue(succeeded.groupValues[1].toDouble() >= 1.0, "방금 성공한 task가 세어져 있다")

		// 워커는 애플리케이션 API를 매핑하지 않는다(불변 조건 2) — 여는 것은 위 둘뿐이다
		assertEquals(404, mockMvc.perform(get("/api/health")).andReturn().response.status)
	}
}
