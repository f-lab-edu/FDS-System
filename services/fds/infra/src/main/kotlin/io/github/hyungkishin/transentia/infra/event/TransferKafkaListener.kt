package io.github.hyungkishin.transentia.infra.event

import io.github.hyungkishin.transentia.application.service.AnalyzeTransferService
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Headers
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class TransferKafkaListener(
    @Value("\${app.transfer.topic}") private val transferTopic: String,
    private val analyzeTransferService: AnalyzeTransferService,
    private val transferEventMapper: TransferEventMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * TODO: 이벤트를 처리하는 쪽의 성능
     * TODO: 메세지 중복처리 할때의 문제 ( 현재 너무 risk ) -> 방어책
     * TODO: offset update 여부 ( Big data tools 로 확인 )
     *
     * - 보내는 쪽과 받는쪽의 쓰루풋을 어떻게 조율 할 것인지
     * - producer 몇대 , consumer 몇대 , 파티션 몇개
     */
    @KafkaListener(
        id = "\${kafka-consumer-config.consumer-group-id}",
        topics = ["\${app.transfer.topic}"],
    )
    fun receive(
        @Payload messages: List<TransferEventAvroModel>,
        @Headers headers: Map<String, Any>
    ) {
        val eventType = headers["eventType"]?.toString()
        val traceId = headers["X-Trace-Id"]?.toString()

        log.info("@@@@@[FDS-Consumer] RECEIVED {} messages, traceId={}", messages.size, traceId)

        // TODO : offset 동작 확인
        messages.forEach { avroMessage ->
            try {
                log.info(
                    "@@@@@[FDS-Consumer] Processing eventId={} amount={} status={}",
                    avroMessage.eventId, avroMessage.amount, avroMessage.status
                )

                // threadPool 배경 -> 현대
                // GC -> 자바에서 / redis | <- CS

                // batch -> SCDF

                val domainEvent = transferEventMapper.toDomain(avroMessage)

                // offset commit 을 할 수 있나 ?
                val riskLog = analyzeTransferService.analyze(domainEvent)

                log.info(
                    "[FDS-Consumer] Analysis complete - eventId={} decision={} hits={}",
                    domainEvent.eventId, riskLog.decision, riskLog.ruleHits.size
                )
                // TODO: Thread.sleep 을 걸었을때의 문제 발생 -> 여러 인스턴스 에서 책정하는것이 명확.
                // TODO: Docker -> 인스턴스 3 대 -> log 확인

            } catch (e: Exception) {
                // TODO: 예외 발생시, 카프카 장애 대응 확인
                // TODO: 카프카 쪽의 영향도 확인
                log.error("[FDS-Consumer] Analysis failed - eventId={}", avroMessage.eventId, e)
                // 재처리를 위해 예외 전파
                throw e
            }
        }
    }
}
