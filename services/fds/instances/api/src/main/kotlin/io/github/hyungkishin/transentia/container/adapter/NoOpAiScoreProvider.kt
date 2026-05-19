package io.github.hyungkishin.transentia.container.adapter

import io.github.hyungkishin.transentia.application.required.AiScoreProvider
import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import org.springframework.stereotype.Component

/**
 * AiScoreProvider 의 NoOp 어댑터. 추후 EsKnnAiScoreAdapter 등으로 교체.
 */
@Component
class NoOpAiScoreProvider : AiScoreProvider {
    override fun score(event: TransferCompleteEvent): Double? = null
}
