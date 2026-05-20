package io.github.hyungkishin.transentia.infra.adapter.`in`.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.hyungkishin.transentia.infra.rdb.entity.SuspiciousPatternAlertJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.SuspiciousPatternAlertJpaRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * suspicious-patterns 토픽을 구독하여 알림을 영속화한다.
 * FraudPatternStreamProcessor 가 발행한 JSON 알림을 수신.
 *
 * 후속 확장: WebSocket 푸시, Slack/이메일 알림, 운영 대시보드 푸시.
 */
@Component
class SuspiciousPatternAlertConsumer(
    private val repository: SuspiciousPatternAlertJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["suspicious-patterns"],
        groupId = "fds-alert-consumer",
        containerFactory = "kafkaListenerContainerFactory",
    )
    @Transactional
    fun consume(payload: String) {
        try {
            val json: JsonNode = objectMapper.readTree(payload)
            val entity =
                SuspiciousPatternAlertJpaEntity(
                    accountId = json["accountId"].asText().toLong(),
                    transferCount = json["transferCount"].asInt(),
                    totalAmount = json["totalAmount"].asLong(),
                    windowMinutes = json["windowMinutes"].asLong(),
                    lastEventId = json["lastEventId"]?.asText(),
                    reason = json["reason"].asText(),
                    detectedAt = Instant.ofEpochMilli(json["detectedAt"].asLong()),
                    traceId = MDC.get("traceId"),
                    rawPayload = payload,
                )
            repository.save(entity)
            log.warn(
                "[FDS-ALERT] 의심 패턴 적재 - accountId={} count={} amount={} reason={}",
                entity.accountId,
                entity.transferCount,
                entity.totalAmount,
                entity.reason,
            )
        } catch (e: Exception) {
            log.error("[FDS-ALERT] 알림 적재 실패 payload={} error={}", payload, e.message, e)
        }
    }
}
