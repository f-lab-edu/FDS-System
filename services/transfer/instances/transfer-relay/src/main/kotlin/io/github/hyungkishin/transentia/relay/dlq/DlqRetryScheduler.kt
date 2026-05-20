package io.github.hyungkishin.transentia.relay.dlq

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * DLQ Retry Worker.
 *
 * 동작: 일정 시간(`dlq.retry.cool-down-minutes`) 이 지난 DEAD_LETTER row 를
 * 한 batch 만큼 PENDING 으로 되돌린다. attempt_count 는 0 으로 리셋되어
 * 재시도 카운트가 새로 시작.
 *
 * 멱등성: SKIP LOCKED 로 동시 실행 안전 (멀티 인스턴스 운영 가정).
 *
 * 운영 정책:
 *  - 영구 실패는 사람이 status 직접 변경 (DEAD_LETTER → ABANDONED 같은 상태 추가
 *    는 후속 PR — 지금은 자동 부활만).
 *  - 운영자 알림은 dlq_dead_letter_count > 0 알림 (prometheus-alerts).
 *
 * 메트릭:
 *  - dlq.dead_letter_count (gauge) — 현재 DEAD_LETTER 수
 *  - dlq.revived (counter) — 누적 revive 수
 */
@Component
class DlqRetryScheduler(
    private val outboxRepository: TransferEventsOutboxRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${dlq.retry.cool-down-minutes:60}") private val coolDownMinutes: Long,
    @Value("\${dlq.retry.batch-size:100}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val deadLetterCount = AtomicLong(0)
    private val revivedCount = AtomicLong(0)

    @PostConstruct
    fun registerMetrics() {
        meterRegistry.gauge("dlq.dead_letter_count", deadLetterCount) {
            it.set(safeCount())
            it.get().toDouble()
        }
        meterRegistry.gauge("dlq.revived", revivedCount) { it.get().toDouble() }
    }

    /**
     * 매 dlq.retry.interval-ms 마다 실행 (default 10분).
     */
    @Scheduled(fixedDelayString = "\${dlq.retry.interval-ms:600000}")
    fun retry() {
        val now = Instant.now()
        val olderThan = now.minus(Duration.ofMinutes(coolDownMinutes))
        try {
            val revived = outboxRepository.reviveDeadLetters(olderThan, batchSize, now)
            if (revived > 0) {
                revivedCount.addAndGet(revived.toLong())
                log.info(
                    "[DLQ-RETRY] {} 건 DEAD_LETTER → PENDING (older than {}min)",
                    revived,
                    coolDownMinutes,
                )
            }
        } catch (e: Exception) {
            log.error("[DLQ-RETRY] 재시도 batch 실패: {}", e.message, e)
        }
    }

    private fun safeCount(): Long =
        try {
            outboxRepository.countDeadLetters()
        } catch (e: Exception) {
            0L
        }
}
