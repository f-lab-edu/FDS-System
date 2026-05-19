package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import io.github.hyungkishin.transentia.container.model.RiskLog

/**
 * FDS 분석 결과 영속화 포트.
 * 입력 이벤트(거래 정보)와 분석 결과(RiskLog)를 함께 저장한다.
 */
interface RiskAnalysisRepository {
    fun save(event: TransferCompleteEvent, riskLog: RiskLog, traceId: String?)
}
