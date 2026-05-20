package io.github.hyungkishin.transentia.common.outbox.transfer

data class ClaimedRow(
    val eventId: Long,
    val payload: String,
    val headers: String,
    val attemptCount: Int = 0,
)
