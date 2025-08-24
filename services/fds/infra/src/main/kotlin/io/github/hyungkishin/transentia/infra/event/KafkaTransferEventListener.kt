package io.github.hyungkishin.transentia.infra.event

import io.github.hyungkishin.transentia.common.message.transfer.TransferCompletedV1
import io.github.hyungkishin.transentia.common.message.transfer.TransferFailedV1
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TransferEventListener {

    @KafkaListener(
        topics = ["transfer.completed"],
        groupId = "fds-consumer",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun onCompleted(event: TransferCompletedV1) {
        println("✅ [FDS] Transfer Completed Event 수신: $event")
        // TODO: Application 계층 서비스 호출 (AnalyzeTransferService)
    }

    @KafkaListener(
        topics = ["transfer.failed"],
        groupId = "fds-consumer",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun onFailed(event: TransferFailedV1) {
        println("❌ [FDS] Transfer Failed Event 수신: $event")
        // TODO: 실패 트랜잭션 분석 or 리스크 로그 기록
    }
}
