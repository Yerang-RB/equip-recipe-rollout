package com.rollout

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.fromApplication
import org.springframework.boot.with
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import java.io.File

/**
 * 실서버 부팅 스모크(`scripts/smoke.sh`)용 테스트 전용 main. `bootTestRun`이 테스트 클래스패스로 돌아
 * 운영 코드에 손대지 않고 embedded postgres가 붙는다. 클록은 운영 빈(시스템 시각) 그대로다.
 */
fun main(args: Array<String>) {
	val running = fromApplication<RolloutApplication>().with(EmbeddedPostgresConfiguration::class).run(*args)
	if (args.any { it == "--smoke-seed" }) seedSmokeAccounts(running.applicationContext)
	if (args.any { it == "--dev-db-url" }) writeDevDbUrl(running.applicationContext)
}

/** `scripts/dev-up.sh`의 워커가 같은 임베디드 DB에 붙도록 JDBC URL을 파일로 넘긴다(포트는 기동마다 다르다). */
private fun writeDevDbUrl(context: ApplicationContext) {
	val postgres = context.getBean(EmbeddedPostgres::class.java)
	File(projectRoot(), "build/dev-db.url").writeText(postgres.getJdbcUrl("postgres", "postgres"))
}

private fun projectRoot(): File =
	generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

/**
 * 스모크의 종단 시나리오가 쓸 계정 셋. **운영 코드가 아니라 테스트 소스**라 배포본에는 없고,
 * 비밀번호는 명령줄(`ps`에 보인다)·env(gradle 데몬 재사용으로 자식에 닿는 보장이 없다)가 아니라
 * 스크립트가 쓴 파일에서 읽는다.
 */
private fun seedSmokeAccounts(context: ApplicationContext) {
	val password = File(projectRoot(), "build/smoke-seed-password").readText().trim()
	require(password.isNotEmpty()) { "스모크 시드 비밀번호가 비었다" }
	val jdbc = context.getBean(JdbcClient::class.java)
	val encoder = context.getBean(PasswordEncoder::class.java)
	listOf("smoke-admin" to "ADMIN", "smoke-operator" to "OPERATOR", "smoke-approver" to "APPROVER").forEach { (username, role) ->
		jdbc.sql(
			"""
			insert into app_user(username, password_hash, display_name, role, enabled, created_at)
			values (:username, :hash, :username, :role, true, now())
			on conflict (username) do nothing
			""".trimIndent(),
		)
			.param("username", username)
			.param("hash", encoder.encode(password))
			.param("role", role)
			.update()
	}
	// 스크립트는 이 줄을 기다린 뒤 로그인한다 — /api/health는 시드보다 먼저 답한다.
	println("스모크 시드 완료")
}
