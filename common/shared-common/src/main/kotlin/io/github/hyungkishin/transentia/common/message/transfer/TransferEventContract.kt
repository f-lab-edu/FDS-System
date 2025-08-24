package io.github.hyungkishin.transentia.common.message.transfer

import java.time.Instant

// 송금시, 공용 이벤트 계약 ( - 원시 타입만 wrapping 하지 않는다. )
sealed interface TransferEventContract {
    val schemaVersion: Int
    val occurredAt: Instant
    val txId: Long
}

data class TransferCompletedV1(
    override val txId: Long,
    val senderUserId: Long,
    val receiverUserId: Long,
    val amountMinor: Long, // Money 객체 rawValue (scale = 8)
    val currency: String = "KRW",
    override val occurredAt: Instant = Instant.now(),
    override val schemaVersion: Int = 1
) : TransferEventContract

data class TransferFailedV1(
    override val txId: Long,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
    override val schemaVersion: Int = 1
) : TransferEventContract