package com.rollout

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 불변 조건 10: 상주 DB 게이트(CHECK·UNIQUE·트리거)는 앱을 우회한 JDBC 문장이 기대한 SQLSTATE로 죽는 것으로
 * 존재를 못박는다. 각 팔은 같은 행의 합법 변형이 들어가는지도 함께 본다 — 다른 이유로 죽어서 통과하는 것을 막는다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class SchemaConstraintTest @Autowired constructor(private val jdbc: JdbcTemplate) {

	private fun assertViolation(sqlState: String, sql: String, vararg args: Any?) {
		try {
			jdbc.update(sql, *args)
		} catch (e: DataAccessException) {
			assertEquals(sqlState, (e.mostSpecificCause as? SQLException)?.sqlState, "SQLSTATE: ${e.mostSpecificCause}")
			return
		}
		fail("제약 위반 문장이 성공했다: $sql ${args.toList()}")
	}

	private val insertUser =
		"insert into app_user(username, password_hash, display_name, role, enabled, created_at) values (?, 'h', 'n', ?, true, now())"

	private fun userId(): Long = jdbc.queryForObject(
		"$insertUser returning id",
		Long::class.java,
		"u-${UUID.randomUUID()}",
		"OPERATOR",
	)!!

	private val insertAudit =
		"insert into audit_log(at, actor_id, actor_name, action, target_type, target_id, detail, result) values (now(), ?, 'n', 'LOGIN', 'USER', 1, '{}'::jsonb, ?)"

	@Test
	fun auditLogRejectsUpdateAndDelete() {
		val actor = userId()
		val id = jdbc.queryForObject("$insertAudit returning id", Long::class.java, actor, "OK")!!

		// 트리거는 plpgsql의 raise exception이라 SQLSTATE는 P0001이다
		assertViolation("P0001", "update audit_log set result = 'DENIED' where id = ?", id)
		assertViolation("P0001", "delete from audit_log where id = ?", id)
		assertEquals(1, jdbc.queryForObject("select count(*)::int from audit_log where id = ?", Int::class.java, id))
		assertEquals("OK", jdbc.queryForObject("select result from audit_log where id = ?", String::class.java, id))
	}

	@Test
	fun statusChecksAndUniquesRejectBypassInserts() {
		val user = userId()
		// CHECK: 역할·감사 결과의 열거 밖 값
		assertViolation("23514", insertUser, "u-${UUID.randomUUID()}", "ROOT")
		assertViolation("23514", insertAudit, user, "MAYBE")

		// UNIQUE: 같은 username 두 번
		val username = "u-${UUID.randomUUID()}"
		jdbc.update(insertUser, username, "ADMIN")
		assertViolation("23505", insertUser, username, "ADMIN")
	}
}
