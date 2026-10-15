package com.rollout

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * 통합 테스트용 실제 postgres(zonky). 샌드박스가 docker 소켓을 막아 Testcontainers를 쓸 수 없다.
 * 아래 옵션 셋 중 하나라도 빠지면 10초 타임아웃 ConnectException으로만 보인다(설계 §9).
 */
@TestConfiguration(proxyBeanMethods = false)
class EmbeddedPostgresConfiguration {

	@Bean(destroyMethod = "close")
	fun embeddedPostgres(): EmbeddedPostgres =
		EmbeddedPostgres.builder()
			// 기본 로케일(ko_KR)이면 initdb가 실패한다
			.setLocaleConfig("locale", "C.UTF-8")
			// 샌드박스에서 유닉스 소켓 생성이 막혀 있어 TCP 전용으로 띄운다
			.setServerConfig("unix_socket_directories", "")
			.setServerConfig("listen_addresses", "127.0.0.1")
			.start()

	@Bean
	fun dataSource(postgres: EmbeddedPostgres): DataSource = postgres.postgresDatabase
}
