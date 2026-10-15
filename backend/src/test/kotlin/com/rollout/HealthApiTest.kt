package com.rollout

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import kotlin.test.assertEquals

@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
@AutoConfigureMockMvc
class HealthApiTest @Autowired constructor(
	private val mockMvc: MockMvc,
	private val jdbc: JdbcTemplate,
) {

	@Test
	fun healthReturnsOk() {
		val response = mockMvc.perform(get("/api/health")).andReturn().response
		assertEquals(200, response.status)
		assertEquals("""{"status":"ok"}""", response.contentAsString)
		// 실DB가 붙었고 Flyway 마이그레이션이 적용됐다
		assertEquals(true, jdbc.queryForObject("select bool_and(success) from flyway_schema_history", Boolean::class.java))
		assertEquals(7, jdbc.queryForObject("select count(*)::int from information_schema.tables where table_schema = 'public' and table_name in ('app_user','equipment','recipe','rollout_job','rollout_task','application_history','audit_log')", Int::class.java))
	}
}
