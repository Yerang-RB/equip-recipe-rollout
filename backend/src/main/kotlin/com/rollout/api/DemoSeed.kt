package com.rollout.api

import com.rollout.job.toDb
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * compose 데모의 초기 데이터(README 60초 시나리오). `app.seed.demo=true`일 때만 api 기동 시 한 번 돈다.
 * 멱등이다 — 유일 키마다 `on conflict do nothing`이라 재기동해도 행이 늘지 않는다.
 * 비밀번호는 `SEED_PASSWORD`에서만 읽는다(기본값 없음 — 없으면 기동 실패, 불변 조건 16). 목업 콘솔 계정의
 * 비밀번호도 같은 값이다(compose가 console-mock에 같은 변수를 넘긴다).
 */
@Component
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class DemoSeed(
	private val jdbc: JdbcClient,
	private val encoder: PasswordEncoder,
	private val clock: Clock,
	private val env: Environment,
	@param:Value("\${app.seed.demo:false}") private val enabled: Boolean,
) : ApplicationRunner {

	@Transactional
	override fun run(args: ApplicationArguments) {
		if (!enabled) return
		val password = env.getRequiredProperty("SEED_PASSWORD")
		require(password.isNotEmpty()) { "SEED_PASSWORD가 비었다" }
		val now = clock.instant().toDb()
		val hash = encoder.encode(password)

		listOf("operator1" to "OPERATOR", "approver1" to "APPROVER", "admin1" to "ADMIN").forEach { (username, role) ->
			jdbc.sql(
				"""
				insert into app_user(username, password_hash, display_name, role, enabled, created_at)
				values (:username, :hash, :username, :role, true, :now)
				on conflict (username) do nothing
				""".trimIndent(),
			).param("username", username).param("hash", hash).param("role", role).param("now", now).update()
		}

		(1..12).forEach { n ->
			val code = "EQ-%02d".format(n)
			val status = when (n) {
				11 -> "STOPPED"
				12 -> "MAINTENANCE"
				else -> "RUNNING"
			}
			jdbc.sql(
				"""
				insert into equipment(code, console_url, model, status, console_user, console_password, created_at)
				values (:code, :url, 'LNS-M', :status, 'console', :password, :now)
				on conflict (code) do nothing
				""".trimIndent(),
			).param("code", code).param("url", "http://console-mock:8090/$code").param("status", status)
				.param("password", password).param("now", now).update()
		}

		// v1 폐기 · v2 활성 · v3 초안 — v3가 시나리오의 「승인」 대상이다(작성자 operator1, 승인자 approver1).
		listOf("LNS-2040", "LNS-2050", "LNS-3010").forEach { partNo ->
			listOf(1 to "RETIRED", 2 to "ACTIVE", 3 to "DRAFT").forEach { (version, status) ->
				jdbc.sql(
					"""
					insert into recipe(part_no, version, params, status, created_by, approved_by, created_at)
					select :partNo, :version, cast(:params as jsonb), :status, o.id, case when :status = 'DRAFT' then null else a.id end, :now
					from app_user o, app_user a
					where o.username = 'operator1' and a.username = 'approver1'
					on conflict (part_no, version) do nothing
					""".trimIndent(),
				).param("partNo", partNo).param("version", version).param("status", status)
					.param("params", """{"mold_temp_c":"${120 + version * 4}","inject_pressure_mpa":"92","hold_pressure_mpa":"45","cooling_sec":"12","cycle_sec":"38"}""")
					.param("now", now).update()
			}
		}
	}
}
