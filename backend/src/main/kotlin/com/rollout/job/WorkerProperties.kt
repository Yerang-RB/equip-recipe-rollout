package com.rollout.job

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 워커 동작 값(불변 조건 5). 기본값을 코드에 두지 않는다 — `application.properties`가 정본이고
 * 값이 빠지면 기동이 실패한다(작업 6의 `ConfigPinTest`가 값을 핀으로 못박는다).
 */
@ConfigurationProperties("worker")
data class WorkerProperties(
	val leaseSeconds: Long,
	val heartbeatSeconds: Long,
	/** 동시 브라우저 세션 상한(워커 층) — 클레임 병렬도보다 작아야 이 값이 실제 상한이다(불변 조건 8). */
	val browserConcurrency: Int,
	val claimParallelism: Int,
	val maxAttempts: Int,
	val backoffBaseSeconds: Long,
	val backoffMaxSeconds: Long,
	/** 클레임 루프가 이 시간 안에 한 번도 돌지 않았으면 워커 헬스는 DOWN이다(불변 조건 11). */
	val loopStaleSeconds: Long,
)
