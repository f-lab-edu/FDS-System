package io.github.hyungkishin.transentia.infra.event

import io.github.hyungkishin.transentia.infra.event.mapper.toContract
import io.github.hyungkishin.transentia.consumer.event.TransferCompletedEvent
import io.github.hyungkishin.transentia.consumer.event.TransferFailedEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * DB 커밋 성공 후에만 해당 리스너가 실행된다.
 * 이벤트가 publish 되었으나, DB 가 롤백이 될경우.
 */
@Component
class KafkaAfterCommitForwarder(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(e: TransferCompletedEvent) {
        val c = e.toContract()
        kafkaTemplate.send("transfer.completed", c.txId.toString(), c)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(e: TransferFailedEvent) {
        val c = e.toContract()
        kafkaTemplate.send("transfer.failed", c.txId.toString(), c)
    }

}