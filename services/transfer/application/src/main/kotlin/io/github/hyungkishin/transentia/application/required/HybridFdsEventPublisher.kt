package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted

interface HybridFdsEventPublisher {
    fun publish(event: TransferCompleted): Boolean
}
