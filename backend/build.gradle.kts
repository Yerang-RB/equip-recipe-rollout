dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// 워커의 콘솔 조작 어댑터(작업 4). api 모드에서는 빈을 만들지 않는다(ApiModeTest).
	implementation("com.microsoft.playwright:playwright:1.62.0")
	// LISTEN/NOTIFY 수신(api의 ProgressListener)이 PGConnection을 직접 쓴다 — 드라이버는 컴파일 경로에도 있다.
	implementation("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// E2E는 목업 콘솔을 같은 JVM 안에서 띄운다(불변 조건 12). 운영 코드는 이 모듈을 import하지 않는다.
	testImplementation(project(":console-mock"))
	// docker 없이 실제 postgres로 통합 테스트(샌드박스가 docker 소켓을 막아 Testcontainers 불가).
	testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
	testImplementation("io.zonky.test:embedded-postgres:2.2.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
