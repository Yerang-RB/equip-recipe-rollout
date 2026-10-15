package com.rollout.job

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 진행 알림은 **커밋 후**에 보낸다(불변 조건 5) — 롤백된 변경을 구독자가 먼저 보는 일이 없게.
 * 수신 쪽(api의 LISTEN → WebSocket)은 작업 6에서 붙는다.
 */
@Component
class ProgressNotifier(private val jdbc: JdbcClient) {

	fun afterCommit(jobId: Long) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			send(jobId)
			return
		}
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() = send(jobId)
		})
	}

	private fun send(jobId: Long) {
		jdbc.sql("select pg_notify('rollout_progress', :payload)")
			.param("payload", jobId.toString())
			.query(String::class.java)
			.optional()
	}
}
