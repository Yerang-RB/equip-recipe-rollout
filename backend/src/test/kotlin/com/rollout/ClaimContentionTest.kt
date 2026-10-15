package com.rollout

import com.rollout.job.ClaimedTask
import com.rollout.job.JobService
import com.rollout.job.TaskClaimer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * 불변 조건 8 DB 층: 한 task는 한 워커만. 판정은 시간·총 호출 수가 아니라 **클레임된 id의 집합과 개수**다.
 */
@Import(EmbeddedPostgresConfiguration::class, TestClockConfiguration::class)
@SpringBootTest(properties = ["mode=api"])
class ClaimContentionTest @Autowired constructor(
	private val jdbc: JdbcTemplate,
	private val dataSource: DataSource,
	private val jobs: JobService,
	private val claimer: TaskClaimer,
) {

	private val fx = Fixtures(jdbc)

	@BeforeEach
	fun closeOpenTasks() = fx.closeOpenTasks()

	private fun newJob(equipmentCount: Int): Long {
		val user = fx.user()
		val recipe = fx.recipe(user)
		val equipment = (1..equipmentCount).map { fx.equipment() }
		return jobs.create(recipe, equipment, user, fx.idempotencyKey()).jobId
	}

	@Test
	fun concurrentClaimersNeverShareATask() {
		newJob(40)
		val workers = 8
		val arrival = CountDownLatch(workers)
		val claimed = ConcurrentLinkedQueue<Long>()
		val errors = ConcurrentLinkedQueue<String>()
		val pool = Executors.newFixedThreadPool(workers)
		try {
			val running = (1..workers).map { w ->
				pool.submit {
					try {
						arrival.countDown()
						arrival.await()
						while (true) {
							val task = claimer.claim("w$w") ?: break
							claimed.add(task.id)
						}
					} catch (e: Throwable) {
						errors.add(e.toString())
					}
				}
			}
			// get 상한은 행 걸림 방지용이고 판정은 아래 세 등호다
			running.forEach { it.get(120, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		assertEquals(emptyList(), errors.toList())
		assertEquals(40, claimed.size, "총 클레임 수")
		assertEquals(40, claimed.toSet().size, "서로 다른 task 수")
	}

	@Test
	fun claimSkipsRowsLockedByAnotherWorker() {
		val job = newJob(2)
		val ids = jdbc.queryForList("select id from rollout_task where job_id = ? order by id", Long::class.java, job)

		dataSource.connection.use { holder ->
			holder.autoCommit = false
			holder.prepareStatement("select id from rollout_task where id = ? for update").use { ps ->
				ps.setLong(1, ids[0]!!)
				ps.executeQuery().use { it.next() }
			}
			val other = Executors.newSingleThreadExecutor()
			try {
				// SKIP LOCKED가 없으면 여기서 남의 잠금에 막혀 돌아오지 못한다(get 상한이 그 증거를 낸다)
				val task: ClaimedTask? = other.submit<ClaimedTask?> { claimer.claim("skipper") }.get(20, TimeUnit.SECONDS)
				assertEquals(ids[1], task?.id, "잠긴 행을 건너뛰고 다음 task를 집는다")
			} finally {
				other.shutdownNow()
				holder.rollback()
			}
		}
	}
}
