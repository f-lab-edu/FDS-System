package io.github.hyungkishin.transentia.container.event

import io.github.hyungkishin.transentia.common.event.DomainEvent

data class TransferEvent(
    val eventId: Long,
    val aggregateType: String,
    val eventType: String,
    val payload: String, // JSON
    val headers: String, // JSON (traceId 등)
) : DomainEvent<TransferEvent>
