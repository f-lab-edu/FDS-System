package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.container.event.TransferEvent
import java.time.Instant

interface TransferEventsOutboxRepository {
    fun save(
        row: TransferEvent,
        now: Instant,
    )

    /**
     * 처리 대기 중인 이벤트 조회 및 claim
     *
     * - PENDING -> SENDING (attempt + 1, watchdog 설정)
     * - SENDING(stuck) -> SENDING (attempt 유지, watchdog 재설정)
     * - watchdog: next_retry_at = now + sendingTimeoutSeconds
     *
     * @param limit 조회 건수
     * @param now 현재 시각
     * @param sendingTimeoutSeconds SENDING 타임아웃 (초)
     */
    fun claimBatch(
        limit: Int,
        now: Instant,
        sendingTimeoutSeconds: Long = 120,
    ): List<ClaimedRow>

    /**
     * Kafka 발행 성공
     */
    fun markAsPublished(
        ids: List<Long>,
        now: Instant,
    )

    /**
     * 재시도 예약
     *
     * SENDING -> PENDING
     */
    fun markForRetry(
        eventId: Long,
        attemptCount: Int,
        nextRetryAt: Instant,
        error: String?,
        now: Instant,
    )

    /**
     * DEAD_LETTER 전환
     *
     * maxAttempts 초과 시
     */
    fun markAsDeadLetter(
        eventId: Long,
        error: String?,
        now: Instant,
    )

    /**
     * DLQ Retry Worker 가 사용. DEAD_LETTER 중 updated_at 이 olderThan 이전인 row 를
     * PENDING 으로 되돌린다. attempt_count 는 0 으로 리셋 (재시도 카운트 새로 시작).
     * 반환: revive 한 row 수.
     */
    fun reviveDeadLetters(
        olderThan: Instant,
        limit: Int,
        now: Instant,
    ): Int

    /**
     * 현재 DEAD_LETTER 인 row 수 — 메트릭/알림용.
     */
    fun countDeadLetters(): Long

    /**
     * PUBLISHED 상태 row 중 olderThan 보다 오래된 것을 archive 테이블로
     * 이동하고 원본 삭제. 같은 트랜잭션 안에서 처리.
     * @return archive 한 row 수.
     */
    fun archivePublished(
        olderThan: Instant,
        limit: Int,
    ): Int
}
