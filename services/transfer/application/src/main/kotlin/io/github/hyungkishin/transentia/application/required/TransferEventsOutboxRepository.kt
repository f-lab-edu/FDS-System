package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.container.event.TransferEvent
import java.time.Instant

interface TransferEventsOutboxRepository {

    fun save(row: TransferEvent, now: Instant)

    /**
     * 처리 대기 중인 이벤트를 조회하고 SENDING 상태로 변경
     *
     * SKIP LOCKED로 동시성 제어
     * 우선순위: PENDING > SENDING(Stuck) > FAILED
     */
    fun claimBatch(
        limit: Int,
        now: Instant,
        sendingTimeoutSeconds: Long = 120
    ): List<ClaimedRow>

    fun markAsPublished(ids: List<Long>, now: Instant)

    fun markFailedWithBackoff(id: Long, cause: String?, backoffMillis: Long, now: Instant)
}
