package com.rollout

import com.rollout.api.DemoSeed
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import java.time.Clock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** compose 데모 시드(작업 9): 플래그 없이는 아무것도 없고, 켜면 정해진 수만큼 한 번만 생긴다. */
class SeedTest : ApiTestSupport() {

	@Autowired private lateinit var jdbcClient: JdbcClient

	@Autowired private lateinit var clock: Clock

	@Test
	fun demoSeedIsIdempotentAndAbsentWithoutFlag() {
		// 이 컨텍스트는 app.seed.demo 없이 떴다 — 기동 때 시드가 돌지 않았다.
		assertEquals(Counts(0, 0, 0), counts(), "플래그 없이는 시드 행이 없다")

		val password = "seed-${UUID.randomUUID()}"
		val seed = DemoSeed(jdbcClient, encoder, clock, MockEnvironment().withProperty("SEED_PASSWORD", password), enabled = true)
		seed.run(DefaultApplicationArguments())
		assertEquals(Counts(3, 12, 9), counts())

		seed.run(DefaultApplicationArguments())
		assertEquals(Counts(3, 12, 9), counts(), "재기동해도 행이 늘지 않는다")

		assertEquals(
			mapOf("RUNNING" to 10, "STOPPED" to 1, "MAINTENANCE" to 1),
			jdbc.queryForList("select status, count(*)::int as n from equipment where code like 'EQ-__' group by status")
				.associate { it["status"] as String to it["n"] as Int },
		)
		assertEquals(
			listOf("RETIRED", "ACTIVE", "DRAFT"),
			jdbc.queryForList("select status from recipe where part_no = 'LNS-2040' order by version", String::class.java),
		)
		val hash = jdbc.queryForObject("select password_hash from app_user where username = 'approver1'", String::class.java)
		assertTrue(encoder.matches(password, hash), "계정 비밀번호는 SEED_PASSWORD다")
		assertTrue(password !in hash!!, "평문이 아니라 해시로 저장된다")

		// 끄면 다시 돌려도 아무 일이 없다(플래그가 유일한 스위치)
		jdbc.update("delete from recipe where part_no = 'LNS-3010' and version = 3")
		DemoSeed(jdbcClient, encoder, clock, MockEnvironment(), enabled = false).run(DefaultApplicationArguments())
		assertEquals(Counts(3, 12, 8), counts())
	}

	private data class Counts(val users: Int, val equipment: Int, val recipes: Int)

	private fun counts() = Counts(
		jdbc.queryForObject("select count(*)::int from app_user where username in ('operator1', 'approver1', 'admin1')", Int::class.java)!!,
		jdbc.queryForObject("select count(*)::int from equipment where code like 'EQ-__'", Int::class.java)!!,
		jdbc.queryForObject("select count(*)::int from recipe where part_no in ('LNS-2040', 'LNS-2050', 'LNS-3010')", Int::class.java)!!,
	)
}
