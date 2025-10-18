package io.github.hyungkishin.transentia.relay.component

import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.relay.model.ProcessingResult
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue

class OutboxWorker(

    private val workerId: Int,

    private val reader: OutboxItemReader,

    private val processor: OutboxEventProcessor,

    private val writer: OutboxEventWriter,

    private val topicName: String,

    private val successIds: ConcurrentLinkedQueue<Long>,

    private val failedEvents: ConcurrentLinkedQueue<ProcessingResult.FailedEvent>

) : Runnable {

    private val log = LoggerFactory.getLogger(javaClass)
    private var processedCount = 0

    override fun run() {
        try {
            while (true) {
                val item = reader.read() ?: break

                processItem(item)

                processedCount++
            }

            log.debug("Worker-{} finished: {} items", workerId, processedCount)
        } catch (e: Exception) {
            log.error("Worker-{} failed", workerId, e)
        }
    }

    private fun processItem(row: ClaimedRow) {
        try {
            val avroModel = processor.process(row)

            writer.write(topicName, avroModel)

            successIds.add(row.eventId)

        } catch (e: Exception) {
            // 실패
            log.warn("Failed to process event: eventId={}, error={}", row.eventId, e.message)
            failedEvents.add(
                ProcessingResult.FailedEvent(
                    eventId = row.eventId,
                    error = e.message ?: "Unknown error",
                    attemptCount = row.attemptCount
                )
            )
        }
    }

}
