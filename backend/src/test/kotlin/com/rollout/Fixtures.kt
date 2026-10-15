package com.rollout

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * 테스트끼리 데이터를 공유하지 않는다(불변 조건 12) — 설비 코드·품번·사용자명을 UUID로 만든다.
 * 고정 행(`created_at`)의 시각은 운영 경로가 아니므로 SQL now()를 쓴다.
 */
class Fixtures(private val jdbc: JdbcTemplate) {

	fun user(role: String = "OPERATOR"): Long = jdbc.queryForObject(
		"insert into app_user(username, password_hash, display_name, role, enabled, created_at) values (?, 'h', '테스터', ?, true, now()) returning id",
		Long::class.java,
		"u-${UUID.randomUUID()}",
		role,
	)!!

	fun equipment(status: String = "RUNNING"): Long = jdbc.queryForObject(
		"insert into equipment(code, console_url, model, status, console_user, console_password, created_at) values (?, 'http://localhost:1/', 'M1', ?, 'cu', 'cp', now()) returning id",
		Long::class.java,
		"E-${UUID.randomUUID()}",
		status,
	)!!

	fun recipe(createdBy: Long, status: String = "ACTIVE"): Long = jdbc.queryForObject(
		"insert into recipe(part_no, version, params, status, created_by, created_at) values (?, 1, '{}'::jsonb, ?, ?, now()) returning id",
		Long::class.java,
		"P-${UUID.randomUUID()}",
		status,
		createdBy,
	)!!

	/** 클레임은 트리 전체에서 고른다 — 다른 테스트가 남긴 미종료 task를 먼저 닫아야 판정이 결정적이다. */
	fun closeOpenTasks() {
		jdbc.update("update rollout_task set status = 'CANCELLED' where status in ('PENDING', 'CLAIMED')")
	}

	fun taskStatus(taskId: Long): String =
		jdbc.queryForObject("select status from rollout_task where id = ?", String::class.java, taskId)!!

	fun jobStatus(jobId: Long): String =
		jdbc.queryForObject("select status from rollout_job where id = ?", String::class.java, jobId)!!

	fun idempotencyKey(): String = "k-${UUID.randomUUID()}"
}
