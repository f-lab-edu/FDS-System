package io.github.hyungkishin.transentia.infra.adapter.`in`.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.hyungkishin.transentia.application.service.AnalyzeTransferService
import io.github.hyungkishin.transentia.infra.config.TracingProcessorSupplier
import io.github.hyungkishin.transentia.infra.event.TransferEventMapper
import io.github.hyungkishin.transentia.infrastructure.kafka.model.TransferEventAvroModel
import org.apache.kafka.streams.kstream.KStream
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Function

/**
 * Transfer 이벤트 단일 처리 Consumer (Kafka Streams)
 * 
 * Port: Driving (Input) Adapter
 * 역할:
 * - transfer-transaction-events 토픽에서 송금 이벤트 수신
 * - 각 송금마다 즉시 FDS 분석 실행
 * - 분석 결과를 fds-analysis-results 토픽으로 발행
 * 
 * 특징:
 * - Stateless 처리 (이전 이벤트 참조 불필요)
 * - 실시간 차단/리뷰 판정
 * - TracingTransformer로 분산 트레이싱 컨텍스트 전파
 */
@Configuration
class TransferEventConsumer(
    private val analyzeTransferService: AnalyzeTransferService,
    private val transferEventMapper: TransferEventMapper,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun processTransferEvents(): Function<KStream<String, TransferEventAvroModel>, KStream<String, String>> {
        return Function { input ->
            input
                .processValues(TracingProcessorSupplier())
                .peek { key, event ->
                    log.info(
                        "[FDS단일분석] 이벤트 수신 - key={} eventId={} accountId={} amount={}",
                        key, event.eventId, event.receiverId, event.amount
                    )
                }
                .mapValues { event ->
                    try {
                        // 1. Domain Event 변환
                        val domainEvent = transferEventMapper.toDomain(event)

                        // 2. FDS 분석 실행 (Application Layer)
                        val riskLog = analyzeTransferService.analyze(domainEvent)

                        // 3. 분석 결과 생성
                        val result = TransferAnalysisResult(
                            eventId = event.eventId.toString(),
                            accountId = event.receiverId.toString(),
                            amount = event.amount.toLong(),
                            decision = riskLog.decision.name,
                            ruleHits = riskLog.ruleHits.map { 
                                RuleHit(
                                    ruleCode = it.ruleCode,
                                    severity = it.severity.name,
                                    reason = it.reason
                                )
                            },
                            riskScore = riskLog.ruleHits.size,
                            success = true,
                            timestamp = System.currentTimeMillis()
                        )

                        log.info(
                            "[FDS단일분석] 분석 완료 - eventId={} decision={} ruleHits={}",
                            event.eventId, result.decision, result.ruleHits.size
                        )

                        // 4. JSON으로 변환하여 출력 토픽에 발행
                        objectMapper.writeValueAsString(result)

                    } catch (e: Exception) {
                        log.error(
                            "[FDS단일분석] 분석 실패 - eventId={} error={}",
                            event.eventId, e.message, e
                        )

                        // 실패 결과
                        val errorResult = TransferAnalysisResult(
                            eventId = event.eventId.toString(),
                            accountId = event.receiverId.toString(),
                            amount = event.amount.toLong(),
                            success = false,
                            error = e.message,
                            timestamp = System.currentTimeMillis()
                        )

                        objectMapper.writeValueAsString(errorResult)
                    } finally {
                        // MDC 정리
                        MDC.clear()
                    }
                }
        }
    }
}

/**
 * FDS 분석 결과 (출력 이벤트)
 */
data class TransferAnalysisResult(
    val eventId: String,
    val accountId: String,
    val amount: Long,
    val decision: String? = null,
    val ruleHits: List<RuleHit> = emptyList(),
    val riskScore: Int = 0,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long
)

/**
 * 룰 히트 정보
 */
data class RuleHit(
    val ruleCode: String,
    val severity: String,
    val reason: String? = null
)
