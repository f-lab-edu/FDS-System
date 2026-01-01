package io.github.hyungkishin.transentia.relay.config

import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import io.github.hyungkishin.transentia.relay.batch.*
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager

typealias OutboxItem = Pair<ClaimedRow, TransferEventAvroModel>

/**
 * Spring Batch Configuration
 */
@Configuration
@EnableScheduling
class TransferOutboxBatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val relayConfig: OutboxRelayConfig,
    private val faultTolerantConfigurer: FaultTolerantStepConfigurer
) {

    @Bean
    fun transferOutboxJob(transferOutboxStep: Step): Job {
        return JobBuilder("transferOutboxJob", jobRepository)
            .start(transferOutboxStep)
            .build()
    }

    @Bean
    fun transferOutboxStep(
        reader: TransferOutboxItemReader,
        processor: TransferOutboxItemProcessor,
        writer: TransferOutboxItemWriter,
        stepListener: TransferOutboxStepListener,
        skipListener: TransferOutboxSkipListener,
        @Qualifier("relayTaskExecutor") taskExecutor: TaskExecutor
    ): Step {
        return StepBuilder("transferOutboxStep", jobRepository)
            .chunk<ClaimedRow, OutboxItem>(relayConfig.chunkSize, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .taskExecutor(taskExecutor)
            .listener(stepListener)
            .listener(skipListener)
            .let { faultTolerantConfigurer.configure(it.faultTolerant()) }
            .build()
    }

    /**
     * Batch TaskExecutor
     *
     * 목적: Spring Batch 전용 스레드풀
     * 이름: relayTaskExecutor (충돌 방지)
     */
    @Bean("relayTaskExecutor")
    fun relayTaskExecutor(): TaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = relayConfig.threadPoolSize
            maxPoolSize = relayConfig.threadPoolSize
            queueCapacity = relayConfig.chunkSize * 2
            setThreadNamePrefix("relay-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(60)
            initialize()
        }
    }

}
