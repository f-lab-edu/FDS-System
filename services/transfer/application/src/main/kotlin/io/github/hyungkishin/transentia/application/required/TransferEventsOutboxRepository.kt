package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.container.event.TransferEvent
import java.time.Instant

/**
 * Transfer Events Outbox Repository
 * 
 * 단순 구조:
 * 1. save: Outbox INSERT
 * 2. claimBatch: PENDING/SENDING(stuck) → SENDING (watchdog 설정)
 * 3. markAsPublished: SENDING → PUBLISHED
 * 4. markForRetry: SENDING → PENDING (재시도)
 * 5. markAsDeadLetter: * → DEAD_LETTER (최종)
 */
interface TransferEventsOutboxRepository {

    fun save(row: TransferEvent, now: Instant)

    /**
     * 처리 대기 중인 이벤트 조회 및 claim
     * 
     * - PENDING → SENDING (attempt + 1, watchdog 설정)
     * - SENDING(stuck) → SENDING (attempt 유지, watchdog 재설정)
     * - watchdog: next_retry_at = now + sendingTimeoutSeconds
     * 
     * @param limit 조회 건수
     * @param now 현재 시각
     * @param sendingTimeoutSeconds SENDING 타임아웃 (초)
     */
    fun claimBatch(
        limit: Int,
        now: Instant,
        sendingTimeoutSeconds: Long = 120
    ): List<ClaimedRow>

    /**
     * Kafka 발행 성공
     */
    fun markAsPublished(ids: List<Long>, now: Instant)

    /**
     * 재시도 예약
     * 
     * SENDING → PENDING
     */
    fun markForRetry(
        eventId: Long,
        attemptCount: Int,
        nextRetryAt: Instant,
        error: String?,
        now: Instant
    )

    /**
     * DEAD_LETTER 전환
     * 
     * maxAttempts 초과 시
     */
    fun markAsDeadLetter(eventId: Long, error: String?, now: Instant)
}
