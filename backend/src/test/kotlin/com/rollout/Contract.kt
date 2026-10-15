package com.rollout

import com.rollout.api.Action
import com.rollout.domain.Role
import org.springframework.core.io.ClassPathResource
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 계약(`openapi.yml`)이 REST의 정본이다(불변 조건 1) — 인가 매트릭스·무인증 401·감사 쓰기 오퍼레이션
 * 전수 테스트는 이 파일에서 (경로, 메서드) 집합을 **읽어** 만든다. 손으로 적은 목록이면 계약에 엔드포인트가
 * 늘어도 테스트는 조용히 지나간다.
 */
object Contract {

	data class Op(val method: String, val path: String, val public: Boolean) {
		override fun toString() = "$method $path"
	}

	val operations: List<Op> by lazy {
		@Suppress("UNCHECKED_CAST")
		val root = ClassPathResource("openapi.yml").inputStream.use { Yaml().load<Map<String, Any>>(it) }
		val paths = root["paths"] as Map<String, Map<String, Any>>
		paths.flatMap { (path, item) ->
			item.filterKeys { it.uppercase() in METHODS }.map { (method, operation) ->
				val security = (operation as Map<String, Any>)["security"] as List<*>?
				Op(method.uppercase(), path, public = security != null && security.isEmpty())
			}
		}
	}

	val writeOperations: List<Op> get() = operations.filter { it.method != "GET" }

	/** 무인증 허용은 **정확히** 넷이다 — 계약의 둘 + actuator 둘(불변 조건 3). */
	val publicPaths: Set<String> get() = operations.filter { it.public }.map { it.path }.toSet() + setOf("/actuator/health", "/actuator/prometheus")

	private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

	/** 저장소 루트(테스트 작업 디렉터리는 `backend/`다). */
	val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
		.first { File(it, "settings.gradle.kts").exists() }
}

/**
 * 설계 §8 매트릭스를 **테스트가 따로 적은** 리터럴이다. 운영의 `Policy`를 읽어 오면 표가 틀려도
 * 둘이 같이 틀려 아무것도 못 잡는다 — 여기 값은 설계 문서에서 옮겨 적는다.
 */
object ExpectedMatrix {

	val allowed: Map<Action, Set<Role>> = mapOf(
		Action.VIEW to setOf(Role.OPERATOR, Role.APPROVER, Role.ADMIN),
		Action.RECIPE_DRAFT to setOf(Role.OPERATOR, Role.APPROVER),
		Action.RECIPE_APPROVE_RETIRE to setOf(Role.APPROVER),
		Action.JOB_CREATE to setOf(Role.OPERATOR, Role.APPROVER),
		Action.JOB_CANCEL_RETRY to setOf(Role.OPERATOR, Role.APPROVER, Role.ADMIN),
		Action.AUDIT_VIEW to setOf(Role.APPROVER, Role.ADMIN),
		Action.USER_EQUIPMENT_MANAGE to setOf(Role.ADMIN),
	)

	/** 계약의 무인증 아닌 오퍼레이션마다 어느 칸이 지키는지. 빠진 것이 있으면 등호 테스트가 잡는다. */
	val actionOf: Map<Contract.Op, Action> = mapOf(
		op("POST", "/api/auth/logout") to Action.VIEW,
		op("GET", "/api/auth/me") to Action.VIEW,
		op("GET", "/api/users") to Action.USER_EQUIPMENT_MANAGE,
		op("POST", "/api/users") to Action.USER_EQUIPMENT_MANAGE,
		op("PUT", "/api/users/{id}") to Action.USER_EQUIPMENT_MANAGE,
		op("GET", "/api/equipment") to Action.VIEW,
		op("POST", "/api/equipment") to Action.USER_EQUIPMENT_MANAGE,
		op("PUT", "/api/equipment/{id}") to Action.USER_EQUIPMENT_MANAGE,
		op("GET", "/api/recipes") to Action.VIEW,
		op("POST", "/api/recipes") to Action.RECIPE_DRAFT,
		op("GET", "/api/recipes/{id}") to Action.VIEW,
		op("PUT", "/api/recipes/{id}") to Action.RECIPE_DRAFT,
		op("POST", "/api/recipes/{id}/approve") to Action.RECIPE_APPROVE_RETIRE,
		op("POST", "/api/recipes/{id}/retire") to Action.RECIPE_APPROVE_RETIRE,
		op("GET", "/api/jobs") to Action.VIEW,
		op("POST", "/api/jobs") to Action.JOB_CREATE,
		op("GET", "/api/jobs/{id}") to Action.VIEW,
		op("POST", "/api/jobs/{id}/cancel") to Action.JOB_CANCEL_RETRY,
		op("POST", "/api/jobs/{id}/retry") to Action.JOB_CANCEL_RETRY,
		op("GET", "/api/history") to Action.VIEW,
		op("GET", "/api/audit") to Action.AUDIT_VIEW,
		op("GET", "/api/ws/jobs/{id}") to Action.VIEW,
	)

	/** 그 칸의 어느 역할에게도 거부되지 않는 오퍼레이션은 403을 만들 수 없다(로그아웃·조회 전원 허용). */
	fun denyingRole(action: Action): Role? = Role.entries.firstOrNull { it !in allowed.getValue(action) }

	private fun op(method: String, path: String) = Contract.Op(method, path, public = false)
}
