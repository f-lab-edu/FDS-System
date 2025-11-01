package io.github.hyungkishin.transentia.infra.event

import io.github.hyungkishin.transentia.application.service.AnalyzeTransferService
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class TransferKafkaListener(
    private val analyzeTransferService: AnalyzeTransferService,
    private val transferEventMapper: TransferEventMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        id = "\${kafka-consumer-config.consumer-group-id}",
        topics = ["\${app.transfer.topic}"],
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun receive(
        @Payload message: TransferEventAvroModel,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        @Header(value = "eventType", required = false) eventType: String?,
        @Header(value = "X-Trace-Id", required = false) traceId: String?,
        consumerRecord: ConsumerRecord<String, TransferEventAvroModel>,
        acknowledgment: Acknowledgment?
    ) {
        try {
            log.debug(
                "[FDS-Consumer] Received - partition={} offset={} eventId={} traceId={}",
                partition, offset, message.eventId, traceId
            )

            // Domain Event 변환
            val domainEvent = transferEventMapper.toDomain(message)

            // FDS 분석 실행
            val riskLog = analyzeTransferService.analyze(domainEvent)

            log.info(
                "[FDS-Consumer] Analysis complete - eventId={} decision={} hits={}",
                domainEvent.eventId,
                riskLog.decision,
                riskLog.ruleHits.size,
            )

            // 수동 커밋 (MANUAL_IMMEDIATE 모드인 경우)
            acknowledgment?.acknowledge()

        } catch (e: Exception) {
            log.error(
                "[FDS-Consumer] Analysis failed - partition={} offset={} eventId={} error={}",
                partition, offset, message.eventId, e.message, e
            )
            // 예외 발생시 재처리를 위해 전파
            throw e
        }
    }

}
