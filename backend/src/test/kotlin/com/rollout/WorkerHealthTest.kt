package com.rollout

import com.rollout.worker.ClaimLoop
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 워커 헬스는 「클레임 루프가 살아 있는가」다(설계 §10). 기다리지 않고 클록을 밀어 판정한다 —
 * 루프 스레드를 먼저 세워야 루프가 스스로 시각을 갱신해 판정이 흔들리지 않는다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=worker"])
@AutoConfigureMockMvc
class WorkerHealthTest @Autowired constructor(
	private val mockMvc: MockMvc,
	private val loop: ClaimLoop,
	private val clock: MutableClock,
) {

	@Test
	fun claimLoopHealthIsDownWhenLoopIsStale() {
		loop.stop()
		assertEquals(200, health(), "막 돌던 루프는 UP이다")

		clock.advance(Duration.ofSeconds(61))
		assertEquals(503, health(), "loop-stale-seconds(60)를 넘긴 루프는 DOWN이다")

		loop.iterateOnce()
		assertEquals(200, health(), "한 번 더 돌면 다시 UP이다")
	}

	private fun health(): Int = mockMvc.perform(get("/actuator/health")).andReturn().response.status
}
