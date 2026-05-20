package io.github.hyungkishin.transentia.container.event

import java.time.Instant

/**
 * 송금 이벤트 도메인 모델 (FDS 검증용)
 */
data class TransferCompleteEvent(
    val eventId: Long,
    val senderId: Long,
    val receiverId: Long,
    val amount: Long,
    val status: String,
    val occurredAt: Instant,
)
