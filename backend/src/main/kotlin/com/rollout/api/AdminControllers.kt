package com.rollout.api

import com.rollout.domain.EquipmentStatus
import com.rollout.domain.Role
import com.rollout.job.toDb
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class UserController(
	private val jdbc: JdbcClient,
	private val clock: Clock,
	private val encoder: PasswordEncoder,
) {

	data class CreateRequest(val username: String, val displayName: String, val role: Role, val password: String) {
		init {
			requireText(username, 50)
			requireText(displayName, 100)
			require(password.isNotEmpty())
		}
	}

	/** 비밀번호는 바꿀 때만 준다 — null이면 기존 해시를 그대로 둔다. */
	data class UpdateRequest(val displayName: String, val role: Role, val enabled: Boolean, val password: String? = null) {
		init {
			requireText(displayName, 100)
			require(password == null || password.isNotEmpty())
		}
	}

	@GetMapping("/api/users")
	fun list(@RequestAttribute(ACTOR) actor: Actor): List<Map<String, Any?>> {
		Policy.check(actor, Action.USER_EQUIPMENT_MANAGE)
		return jdbc.sql("select id, username, display_name, role, enabled from app_user order by id").query(::userRow).list()
	}

	@PostMapping("/api/users")
	fun create(@RequestAttribute(ACTOR) actor: Actor, @RequestBody body: CreateRequest): ResponseEntity<Map<String, Any?>> {
		Policy.check(actor, Action.USER_EQUIPMENT_MANAGE)
		requirePasswordFits(body.password)
		val id = jdbc.sql(
			"""
			insert into app_user(username, password_hash, display_name, role, enabled, created_at)
			values (:username, :hash, :displayName, :role, true, :now) returning id
			""".trimIndent(),
		)
			.param("username", body.username)
			.param("hash", encoder.encode(body.password))
			.param("displayName", body.displayName)
			.param("role", body.role.name)
			.param("now", clock.instant().toDb())
			.query(Long::class.java)
			.single()
		return ResponseEntity.status(201).body(one(id))
	}

	@PutMapping("/api/users/{id}")
	fun update(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long, @RequestBody body: UpdateRequest): Map<String, Any?> {
		Policy.check(actor, Action.USER_EQUIPMENT_MANAGE)
		body.password?.let(::requirePasswordFits)
		val changed = jdbc.sql(
			"""
			update app_user set display_name = :displayName, role = :role, enabled = :enabled,
			    password_hash = coalesce(cast(:hash as varchar), password_hash)
			where id = :id
			""".trimIndent(),
		)
			.param("displayName", body.displayName)
			.param("role", body.role.name)
			.param("enabled", body.enabled)
			.param("hash", body.password?.let(encoder::encode))
			.param("id", id)
			.update()
		if (changed == 0) notFound()
		return one(id)
	}

	private fun one(id: Long): Map<String, Any?> =
		jdbc.sql("select id, username, display_name, role, enabled from app_user where id = :id")
			.param("id", id)
			.query(::userRow)
			.single()
}

/** 응답에 해시가 들어갈 자리가 없다 — 열을 하나씩 적는다(`select *`였다면 password_hash가 새어 나간다). */
private fun userRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): Map<String, Any?> = mapOf(
	"id" to rs.getLong("id"),
	"username" to rs.getString("username"),
	"displayName" to rs.getString("display_name"),
	"role" to rs.getString("role"),
	"enabled" to rs.getBoolean("enabled"),
)

@RestController
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class EquipmentController(private val jdbc: JdbcClient, private val clock: Clock) {

	data class CreateRequest(
		val code: String,
		val consoleUrl: String,
		val model: String,
		val status: EquipmentStatus,
		val consoleUser: String,
		val consolePassword: String,
	) {
		init {
			requireText(code, 50)
			requireConsoleUrl(consoleUrl)
			requireText(model, 100)
			requireText(consoleUser, 100)
			requireText(consolePassword, 200)
		}
	}

	data class UpdateRequest(
		val consoleUrl: String,
		val model: String,
		val status: EquipmentStatus,
		val consoleUser: String,
		val consolePassword: String? = null,
	) {
		init {
			requireConsoleUrl(consoleUrl)
			requireText(model, 100)
			requireText(consoleUser, 100)
			consolePassword?.let { requireText(it, 200) }
		}
	}

	@GetMapping("/api/equipment")
	fun list(@RequestAttribute(ACTOR) actor: Actor): List<Map<String, Any?>> {
		Policy.check(actor, Action.VIEW)
		return jdbc.sql(
			"""
			select e.id, e.code, e.console_url, e.model, e.status,
			       (select h.part_no || ' v' || h.version from application_history h
			        where h.equipment_id = e.id and h.result = 'APPLIED'
			        order by h.applied_at desc, h.id desc limit 1) as last_applied
			from equipment e order by e.code
			""".trimIndent(),
		).query(::equipmentRow).list()
	}

	@PostMapping("/api/equipment")
	fun create(@RequestAttribute(ACTOR) actor: Actor, @RequestBody body: CreateRequest): ResponseEntity<Map<String, Any?>> {
		Policy.check(actor, Action.USER_EQUIPMENT_MANAGE)
		val id = jdbc.sql(
			"""
			insert into equipment(code, console_url, model, status, console_user, console_password, created_at)
			values (:code, :url, :model, :status, :user, :password, :now) returning id
			""".trimIndent(),
		)
			.param("code", body.code)
			.param("url", body.consoleUrl)
			.param("model", body.model)
			.param("status", body.status.name)
			.param("user", body.consoleUser)
			.param("password", body.consolePassword)
			.param("now", clock.instant().toDb())
			.query(Long::class.java)
			.single()
		return ResponseEntity.status(201).body(one(id))
	}

	@PutMapping("/api/equipment/{id}")
	fun update(@RequestAttribute(ACTOR) actor: Actor, @PathVariable id: Long, @RequestBody body: UpdateRequest): Map<String, Any?> {
		Policy.check(actor, Action.USER_EQUIPMENT_MANAGE)
		val currentUrl = jdbc.sql("select console_url from equipment where id = :id").param("id", id)
			.query(String::class.java).optional().orElseGet { notFound() }
		// 워커는 저장된 비밀번호를 이 URL의 로그인 폼에 넣는다 — 비밀번호 없이 URL만 바꾸면 응답이 숨긴 비밀번호를
		// 다른 호스트로 보낼 수 있다(리뷰 AR6). URL을 바꾸려면 비밀번호도 함께 준다.
		if (body.consoleUrl != currentUrl && body.consolePassword == null) invalid("콘솔 URL을 바꾸면 콘솔 비밀번호도 함께 줘야 한다")
		val changed = jdbc.sql(
			"""
			update equipment set console_url = :url, model = :model, status = :status, console_user = :user,
			    console_password = coalesce(cast(:password as varchar), console_password)
			where id = :id
			""".trimIndent(),
		)
			.param("url", body.consoleUrl)
			.param("model", body.model)
			.param("status", body.status.name)
			.param("user", body.consoleUser)
			.param("password", body.consolePassword)
			.param("id", id)
			.update()
		if (changed == 0) notFound()
		return one(id)
	}

	private fun one(id: Long): Map<String, Any?> =
		jdbc.sql("select id, code, console_url, model, status, null as last_applied from equipment where id = :id")
			.param("id", id)
			.query(::equipmentRow)
			.single()
}

/**
 * 콘솔 자격증명(계정·비밀번호)은 어느 응답 경로에도 열로 들어가지 않는다(불변 조건 16 · 설계 §8 — 리뷰 AR4).
 * 천장: ADMIN도 저장된 콘솔 계정을 화면에서 볼 수 없다 — 바꿀 때는 새로 적는다.
 */
private fun equipmentRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): Map<String, Any?> = mapOf(
	"id" to rs.getLong("id"),
	"code" to rs.getString("code"),
	"consoleUrl" to rs.getString("console_url"),
	"model" to rs.getString("model"),
	"status" to rs.getString("status"),
	"lastApplied" to rs.getString("last_applied"),
)

/** 콘솔은 워커 브라우저가 여는 주소다 — http(s) 절대 URL만 받는다(리뷰 B8). */
private fun requireConsoleUrl(url: String) {
	requireText(url, 500)
	require(Regex("^https?://[^\\s/]+.*$").matches(url))
}
