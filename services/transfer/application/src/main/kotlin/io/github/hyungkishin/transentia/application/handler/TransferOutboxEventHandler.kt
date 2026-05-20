package io.github.hyungkishin.transentia.application.handler

import io.github.hyungkishin.transentia.application.port.TransferEventPublisher
import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class TransferOutboxEventHandler(
    private val eventPublisher: TransferEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("outboxEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: TransferCompleted) {
        log.debug("비동기 Kafka 전송 시도: transactionId={}", event.transactionId)

        eventPublisher.publish(event)
    }
}
