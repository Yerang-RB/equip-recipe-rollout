package com.rollout.e2e

import com.rollout.EmbeddedPostgresConfiguration
import com.rollout.job.JobService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.fail

/** 이 런의 표준 레시피 값(설계 §4 params 예 그대로 — 전부 목업 콘솔의 허용 범위 안이다). */
val STANDARD_PARAMS: Map<String, String> = linkedMapOf(
	"mold_temp_c" to "128",
	"inject_pressure_mpa" to "92",
	"hold_pressure_mpa" to "45",
	"cooling_sec" to "12",
	"cycle_sec" to "38",
)

data class TestRecipe(val id: Long, val partNo: String, val version: Int)

/**
 * E2E의 공통 하네스: 워커 모드로 뜬 컨텍스트 + 같은 JVM의 목업 콘솔. 워커가 돌고 있으므로
 * 테스트는 상태를 직접 만들지 않고 **잡을 만들어 두고 끝날 때까지 기다린다** — 판정은 상태·횟수로 한다
 * (대기 상한은 행 걸림 방지용이지 판정이 아니다).
 */
@Import(EmbeddedPostgresConfiguration::class, ConsoleMockConfiguration::class)
@SpringBootTest(properties = ["mode=worker", "worker.backoff-base-seconds=0"])
@AutoConfigureMockMvc
abstract class E2ESupport {

	@Autowired protected lateinit var jdbc: JdbcTemplate

	@Autowired protected lateinit var jobs: JobService

	@Autowired protected lateinit var console: ConsoleMock

	protected fun user(): Long = jdbc.queryForObject(
		"insert into app_user(username, password_hash, display_name, role, enabled, created_at) values (?, 'h', '테스터', 'OPERATOR', true, now()) returning id",
		Long::class.java,
		"u-${UUID.randomUUID()}",
	)!!

	/** 설비 한 대 = 콘솔 한 대. `console_url`이 이 JVM에 뜬 목업의 그 설비 경로를 가리킨다. */
	protected fun equipment(status: String = "RUNNING"): Pair<Long, String> {
		val code = "EQ-${UUID.randomUUID().toString().take(8)}"
		val id = jdbc.queryForObject(
			"insert into equipment(code, console_url, model, status, console_user, console_password, created_at) values (?, ?, 'LNS-M', ?, ?, ?, now()) returning id",
			Long::class.java,
			code,
			console.stationUrl(code),
			status,
			CONSOLE_USER,
			CONSOLE_PASSWORD,
		)!!
		return id to code
	}

	protected fun recipe(createdBy: Long, params: Map<String, String> = STANDARD_PARAMS): TestRecipe {
		val partNo = "P-${UUID.randomUUID().toString().take(8)}"
		val json = params.entries.joinToString(",", "{", "}") { """"${it.key}":${it.value}""" }
		val id = jdbc.queryForObject(
			"insert into recipe(part_no, version, params, status, created_by, created_at) values (?, 1, ?::jsonb, 'ACTIVE', ?, now()) returning id",
			Long::class.java,
			partNo,
			json,
			createdBy,
		)!!
		return TestRecipe(id, partNo, 1)
	}

	protected fun deploy(recipe: TestRecipe, equipmentIds: List<Long>, requestedBy: Long): Long =
		jobs.create(recipe.id, equipmentIds, requestedBy, "k-${UUID.randomUUID()}").jobId

	/** 잡이 종료 상태가 될 때까지 기다린다. 상한을 넘기면 진단(태스크 상태·마지막 오류)과 함께 실패. */
	protected fun awaitJob(jobId: Long): String {
		val deadline = System.nanoTime() + AWAIT_LIMIT_NANOS
		while (System.nanoTime() < deadline) {
			val status = jdbc.queryForObject("select status from rollout_job where id = ?", String::class.java, jobId)!!
			if (status in setOf("SUCCEEDED", "PARTIAL", "FAILED")) return status
			Thread.sleep(100)
		}
		fail("잡 $jobId 이 끝나지 않았다: ${tasks(jobId)}")
	}

	protected fun tasks(jobId: Long): List<Map<String, Any?>> =
		jdbc.queryForList("select equipment_id, status, attempt, last_error from rollout_task where job_id = ? order by id", jobId)

	protected fun historyCount(equipmentId: Long, result: String): Int = jdbc.queryForObject(
		"select count(*)::int from application_history where equipment_id = ? and result = ?",
		Int::class.java,
		equipmentId,
		result,
	)!!

	private companion object {
		const val AWAIT_LIMIT_NANOS = 180_000_000_000L
	}
}
