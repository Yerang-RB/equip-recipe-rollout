package com.rollout

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.socket.server.support.WebSocketHandlerMapping
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 계약(`openapi.yml`)이 선언한 엔드포인트 집합 == 스프링이 실제로 노출하는 매핑 집합(**양방향**).
 * 기대값은 [Contract]가 계약 파일에서 읽는다 — 이 파일에 경로 하드코딩이 없다.
 * WebSocket은 `RequestMappingHandlerMapping`에 없으므로 핸드셰이크 매핑에서 따로 읽는다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class ContractEndpointTest @Autowired constructor(
	@param:Qualifier("requestMappingHandlerMapping")
	private val handlerMapping: RequestMappingHandlerMapping,
	private val webSocketMapping: WebSocketHandlerMapping,
) {

	/** 경로 변수 이름은 계약과 구현이 각자 정한다(WS는 이름 없는 `*`다) — 이름을 지우고 위치만 비교한다. */
	private fun normalize(path: String) = path.replace(Regex("\\{[^/}]+}"), "{}").replace("/*", "/{}")

	private fun springMappings(): Set<Pair<String, String>> {
		val rest = handlerMapping.handlerMethods
			// BasicErrorController(`/error`)는 프레임워크의 에러 디스패치 핸들러라 계약에 없다.
			.filterValues { it.beanType != BasicErrorController::class.java }
			.flatMap { (info, _) ->
				val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
				val methods = info.methodsCondition.methods.map { it.name }
				// 둘 중 하나라도 비면 flatMap이 0개를 내 그 매핑이 통째로 사라진다 — 메서드 미지정
				// @RequestMapping(전 메서드 수용)이 그 경우라 여기서 먼저 실패시킨다.
				check(patterns.isNotEmpty() && methods.isNotEmpty()) { "경로 또는 HTTP 메서드가 지정되지 않은 매핑: $info" }
				patterns.flatMap { p -> methods.map { m -> normalize(p) to m } }
			}
		// 핸드셰이크는 GET이다(업그레이드 요청).
		val webSocket = webSocketMapping.urlMap.keys.map { normalize(it) to "GET" }
		return (rest + webSocket).toSet()
	}

	@Test
	fun contractEndpointSetMatchesActualMappings() {
		val contract = Contract.operations.map { normalize(it.path) to it.method }.toSet()
		val spring = springMappings()

		assertTrue(contract.isNotEmpty(), "계약에서 오퍼레이션을 하나도 못 읽었다(로더 고장)")
		assertTrue(spring.isNotEmpty(), "스프링 매핑을 하나도 못 읽었다")
		assertTrue("/api/ws/jobs/{}" to "GET" in spring, "WebSocket 핸드셰이크 매핑을 못 읽었다")
		assertEquals(emptySet(), contract - spring, "계약에 있으나 구현이 노출하지 않는 엔드포인트")
		assertEquals(emptySet(), spring - contract, "계약에 없는데 구현이 노출하는 엔드포인트")
	}
}
