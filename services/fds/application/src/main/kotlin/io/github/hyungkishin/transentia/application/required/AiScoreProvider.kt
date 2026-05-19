package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent

/**
 * 거래 이상행위 점수(0.0~1.0) 제공 포트. 미구현/장애 시 null.
 * ML 모델 어댑터는 별도 PR 에서 교체.
 */
interface AiScoreProvider {
    fun score(event: TransferCompleteEvent): Double?
}
