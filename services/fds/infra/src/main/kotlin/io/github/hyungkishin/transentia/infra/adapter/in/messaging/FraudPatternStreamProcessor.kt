package io.github.hyungkishin.transentia.infra.adapter.`in`.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.TimeWindows
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.function.Function

/**
 * 의심 패턴 탐지 Stream Processor (Kafka Streams)
 * 
 * Port: Driving (Input) Adapter
 * 역할:
 * - transfer-transaction-events 토픽에서 송금 이벤트 수신
 * - 계좌별 10분 윈도우 집계
 * - 의심 패턴 탐지 (분산송금, 자금세탁)
 * - suspicious-patterns 토픽으로 알림 발행
 * 
 * 특징:
 * - Stateful 처리 (State Store 사용)
 * - Time Windowing (10분 단위)
 * - 패턴 기반 탐지
 */
@Configuration
class FraudPatternStreamProcessor(
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val WINDOW_SIZE_MINUTES = 10L
        private const val SUSPICIOUS_TRANSFER_COUNT = 5        // 10분간 5건 이상
        private const val SUSPICIOUS_AMOUNT_THRESHOLD = 20_000_000L  // 2천만원
    }

    @Bean
    fun detectSuspiciousPatterns(): Function<KStream<String, TransferEventAvroModel>, KStream<String, String>> {
        return Function { input ->
            input
                .peek { _, event ->
                    log.debug(
                        "[10분집계] 이벤트 수신 - accountId={} amount={}",
                        event.receiverId, event.amount
                    )
                }
                
                // 계좌 ID로 키 변경 (같은 계좌끼리 그룹화)
                .selectKey { _, event -> event.receiverId.toString() }
                
                // 계좌별로 그룹화
                .groupByKey()
                
                // 10분 윈도우 설정
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(WINDOW_SIZE_MINUTES)))
                
                // 계좌별 송금 통계 집계
                .aggregate(
                    // 초기값
                    { AccountTransferStats() },
                    
                    // 집계 로직
                    { accountId, event, stats ->
                        stats.copy(
                            accountId = accountId,
                            count = stats.count + 1,
                            totalAmount = stats.totalAmount + event.amount.toLong(),
                            lastEventId = event.eventId.toString(),
                            lastTimestamp = System.currentTimeMillis()
                        )
                    },
                    
                    // State Store 설정
                    Materialized.with(
                        Serdes.String(),
                        AccountTransferStatsSerde(objectMapper)
                    )
                )
                
                // 윈도우 결과를 스트림으로 변환
                .toStream()
                
                // 윈도우 키를 단순 문자열 키로 변환
                .selectKey { windowedKey, _ -> windowedKey.key() }
                
                // 의심 패턴 필터링
                .filter { accountId, stats ->
                    val isSuspicious = stats.count >= SUSPICIOUS_TRANSFER_COUNT ||
                            stats.totalAmount >= SUSPICIOUS_AMOUNT_THRESHOLD
                    
                    if (isSuspicious) {
                        log.warn(
                            "[10분집계] 의심 패턴 탐지ㅛ accountId={} count={} amount={} window={}분",
                            accountId, stats.count, stats.totalAmount, WINDOW_SIZE_MINUTES
                        )
                    }
                    
                    isSuspicious
                }
                
                // 알림 이벤트로 변환
                .mapValues { accountId, stats ->
                    val alert = SuspiciousPatternAlert(
                        accountId = accountId,
                        transferCount = stats.count,
                        totalAmount = stats.totalAmount,
                        windowMinutes = WINDOW_SIZE_MINUTES,
                        lastEventId = stats.lastEventId,
                        reason = buildAlertReason(stats),
                        detectedAt = System.currentTimeMillis()
                    )
                    
                    log.warn(
                        "[10분집계] 알림 발행 - accountId={} reason={}",
                        accountId, alert.reason
                    )
                    
                    // JSON으로 변환하여 토픽 발행
                    objectMapper.writeValueAsString(alert)
                }
        }
    }

    /**
     * 의심 패턴 이유 생성
     */
    private fun buildAlertReason(stats: AccountTransferStats): String {
        val reasons = mutableListOf<String>()
        
        if (stats.count >= SUSPICIOUS_TRANSFER_COUNT) {
            reasons.add("${WINDOW_SIZE_MINUTES}분간 ${stats.count}건 송금 (분산송금 의심)")
        }
        
        if (stats.totalAmount >= SUSPICIOUS_AMOUNT_THRESHOLD) {
            reasons.add("${WINDOW_SIZE_MINUTES}분간 총 ${stats.totalAmount.formatAmount()}원 (자금세탁 의심)")
        }
        
        return reasons.joinToString(" / ")
    }

    private fun Long.formatAmount(): String {
        return String.format("%,d", this)
    }
}

/**
 * 계좌별 송금 통계 (Windowed Aggregation)
 */
data class AccountTransferStats(
    val accountId: String = "",
    val count: Int = 0,
    val totalAmount: Long = 0L,
    val lastEventId: String = "",
    val lastTimestamp: Long = 0L
)

/**
 * 의심 패턴 알림 (출력 이벤트)
 */
data class SuspiciousPatternAlert(
    val accountId: String,
    val transferCount: Int,
    val totalAmount: Long,
    val windowMinutes: Long,
    val lastEventId: String,
    val reason: String,
    val detectedAt: Long
)

/**
 * AccountTransferStats Serde (State Store용)
 */
class AccountTransferStatsSerde(
    private val objectMapper: ObjectMapper
) : org.apache.kafka.common.serialization.Serde<AccountTransferStats> {

    override fun serializer() = org.apache.kafka.common.serialization.Serializer<AccountTransferStats> { _, data ->
        objectMapper.writeValueAsBytes(data)
    }

    override fun deserializer() = org.apache.kafka.common.serialization.Deserializer<AccountTransferStats> { _, data ->
        objectMapper.readValue(data, AccountTransferStats::class.java)
    }
}
