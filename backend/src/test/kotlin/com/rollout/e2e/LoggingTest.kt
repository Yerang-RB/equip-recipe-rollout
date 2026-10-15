package com.rollout.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 구조화 로깅(불변 조건 11): task 실행 구간의 줄은 JSON이고 `job_id`·`task_id`를 달고 있다.
 * 부재 단언(콘솔 자격증명 없음)은 **양의 앵커**(같은 task의 적용 완료 줄이 실제로 있다)와 함께 한다 —
 * 로그가 통째로 비어도 통과하는 부재 단언은 아무것도 지키지 않는다.
 */
@ExtendWith(OutputCaptureExtension::class)
class LoggingTest : E2ESupport() {

	@Test
	fun taskLogLinesAreJsonWithJobAndTaskIdAndNoCredentials(output: CapturedOutput) {
		val requester = user()
		val (equipmentId, _) = equipment()
		val jobId = deploy(recipe(requester), listOf(equipmentId), requester)

		assertEquals("SUCCEEDED", awaitJob(jobId))

		val jsonLines = output.all.lineSequence().filter { it.startsWith("{") }.toList()
		val applied = jsonLines.filter { it.contains("적용 완료") && it.contains(""""job_id":"$jobId"""") }
		assertTrue(applied.isNotEmpty(), "양의 앵커: 이 잡의 적용 완료 줄이 JSON으로 남는다")
		applied.forEach { assertTrue(it.contains(""""task_id":"""), "task_id MDC가 붙는다: $it") }
		jsonLines.forEach {
			assertTrue(CONSOLE_PASSWORD !in it, "콘솔 비밀번호가 로그에 없다: $it")
			assertTrue(CONSOLE_USER !in it, "콘솔 계정이 로그에 없다: $it")
		}
	}
}
