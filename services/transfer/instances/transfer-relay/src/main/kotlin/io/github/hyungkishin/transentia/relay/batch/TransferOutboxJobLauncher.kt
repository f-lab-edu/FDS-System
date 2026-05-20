package io.github.hyungkishin.transentia.relay.batch

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Spring Batch Job 실행
 *
 * 기존: TransferOutboxRelay
 * 개선: ExecutorService 수동 관리 -> JobLauncher 사용
 */
@Component
class TransferOutboxJobLauncher(
    private val jobLauncher: JobLauncher,
    private val transferOutboxJob: Job,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.outbox.relay.fixedDelayMs:2000}",
        initialDelayString = "\${app.outbox.relay.initialDelayMs:5000}",
    )
    fun runJob() {
        try {
            val jobParameters =
                JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters()

            val jobExecution = jobLauncher.run(transferOutboxJob, jobParameters)

            log.debug("Job 실행 완료: {}", jobExecution.exitStatus.exitCode)
        } catch (e: Exception) {
            log.error("Job 실행 실패", e)
        }
    }
}
