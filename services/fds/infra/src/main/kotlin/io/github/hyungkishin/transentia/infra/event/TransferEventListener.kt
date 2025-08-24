package io.github.hyungkishin.transentia.infra.event

import io.github.hyungkishin.transentia.common.message.transfer.TransferCompletedV1
import io.github.hyungkishin.transentia.common.message.transfer.TransferFailedV1
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TransferEventListener {

    @KafkaListener(topics = ["transfer.completed"], groupId = "fds-consumer")
    fun onCompleted(event: TransferCompletedV1) {
        println("TransferCompletedV1 received: $event")
    }

    @KafkaListener(topics = ["transfer.failed"], groupId = "fds-consumer")
    fun onFailed(event: TransferFailedV1) {
        println("TransferFailedV1 received: $event")
    }
}