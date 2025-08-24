package io.github.hyungkishin.transentia.infra.event.mapper

import io.github.hyungkishin.transentia.common.message.transfer.TransferCompletedV1
import io.github.hyungkishin.transentia.common.message.transfer.TransferFailedV1
import io.github.hyungkishin.transentia.consumer.event.TransferCompletedEvent
import io.github.hyungkishin.transentia.consumer.event.TransferFailedEvent

fun TransferCompletedEvent.toContract() = TransferCompletedV1(
    txId = transactionId.value,
    senderUserId = senderUserId.value,
    receiverUserId = receiverUserId.value,
    amountMinor = amount.rawValue,
)

fun TransferFailedEvent.toContract() = TransferFailedV1(
    txId = transactionId.value,
    reason = reason
)