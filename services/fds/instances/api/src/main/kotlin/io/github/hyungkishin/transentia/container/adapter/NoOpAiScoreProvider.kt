package io.github.hyungkishin.transentia.container.adapter

import io.github.hyungkishin.transentia.application.required.AiScoreProvider
import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * AiScoreProvider 의 NoOp 어댑터.
 * fds.ai.score-provider=noop 시 활성. default 는 heuristic.
 */
@Component
@ConditionalOnProperty(name = ["fds.ai.score-provider"], havingValue = "noop")
class NoOpAiScoreProvider : AiScoreProvider {
    override fun score(event: TransferCompleteEvent): Double? = null
}
