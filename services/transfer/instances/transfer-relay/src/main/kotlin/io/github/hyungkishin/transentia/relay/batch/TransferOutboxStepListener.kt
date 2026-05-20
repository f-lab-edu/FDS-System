package io.github.hyungkishin.transentia.relay.batch

import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.stereotype.Component

@Component
class TransferOutboxStepListener(
    private val reader: TransferOutboxItemReader,
) : StepExecutionListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun beforeStep(stepExecution: StepExecution) {
        reader.reset()
        log.info("Step 시작: {}", stepExecution.stepName)
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        val duration =
            stepExecution.endTime?.let {
                Duration.between(stepExecution.startTime, it).toMillis()
            } ?: 0

        log.info(
            "Step 완료: 읽기={}, 쓰기={}, 커밋={}, 롤백={}, Skip={}, 소요={}ms",
            stepExecution.readCount,
            stepExecution.writeCount,
            stepExecution.commitCount,
            stepExecution.rollbackCount,
            stepExecution.skipCount,
            duration,
        )

        return stepExecution.exitStatus
    }
}
