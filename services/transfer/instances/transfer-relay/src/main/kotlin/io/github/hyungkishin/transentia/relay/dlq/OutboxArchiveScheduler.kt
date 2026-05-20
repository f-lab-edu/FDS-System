package io.github.hyungkishin.transentia.relay.dlq

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Outbox archive Worker.
 *
 * LIMITATIONS '2.1 Outbox 누적' 자리 해소.
 * PUBLISHED 상태 row 중 retention(기본 14일) 지난 것을
 * transfer_events_archive 테이블로 이동 후 원본 삭제.
 *
 * 정책:
 *  - 매 archive.outbox.interval-ms (기본 24h) 마다 1회 실행.
 *  - 한 batch 당 archive.outbox.batch-size (기본 1000) 건.
 *  - SKIP LOCKED 로 멀티 인스턴스 안전.
 *
 * 메트릭:
 *  - outbox.archived (counter) — 누적 archive 건수.
 */
@Component
class OutboxArchiveScheduler(
    private val outboxRepository: TransferEventsOutboxRepository,
    meterRegistry: MeterRegistry,
    @Value("\${archive.outbox.retention-days:14}") private val retentionDays: Long,
    @Value("\${archive.outbox.batch-size:1000}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val archivedCounter: Counter =
        Counter
            .builder("outbox.archived")
            .description("transfer_events PUBLISHED row 가 archive 테이블로 이동한 누적 수")
            .register(meterRegistry)

    @Scheduled(fixedDelayString = "\${archive.outbox.interval-ms:86400000}")
    @Transactional
    fun archive() {
        val olderThan = Instant.now().minus(Duration.ofDays(retentionDays))
        try {
            var total = 0
            // 한 번에 batch-size 만큼씩, 더 이상 없을 때까지.
            // (운영에서 너무 길어지면 max 반복 횟수 추가 검토.)
            while (true) {
                val moved = outboxRepository.archivePublished(olderThan, batchSize)
                if (moved == 0) break
                total += moved
                archivedCounter.increment(moved.toDouble())
            }
            if (total > 0) {
                log.info(
                    "[outbox-archive] {} 건 이동 (older than {} days)",
                    total,
                    retentionDays,
                )
            }
        } catch (e: Exception) {
            log.error("[outbox-archive] 실패: {}", e.message, e)
        }
    }
}
