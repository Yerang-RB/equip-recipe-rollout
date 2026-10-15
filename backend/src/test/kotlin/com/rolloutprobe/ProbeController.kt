package com.rolloutprobe

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicInteger

/**
 * 본문 상한·엄격 JSON의 「파싱 전에 끊었다」를 재는 프로브. 호출 **수**가 증거라 업무 컨트롤러가 아니라
 * 이 자리에서 센다. 패키지가 `com.rollout` 밖이라 스캔에 걸리지 않고 [ErrorHandlingTest]만 `@Import`한다
 * (계약↔매핑 등호를 보는 컨텍스트에는 들어가지 않는다).
 */
@RestController
class ProbeController {

	val bodyCalls = AtomicInteger()

	data class Body(val name: String)

	@PostMapping("/probe/body")
	fun body(@RequestBody body: Body): Map<String, String> {
		bodyCalls.incrementAndGet()
		return mapOf("name" to body.name)
	}
}
