package io.github.hyungkishin.transentia.consumer.model

import io.github.hyungkishin.transentia.consumer.enums.FinalDecisionType
import java.time.Instant

/**
 * 1건의 송금(transferId)에 대한 FDS 평가 결과이다.
 * 최종판정 과 근거를 기준으로 한다.
 */
class RiskLog private constructor(
    val id: Long?,
    val txId: Long,
    val decision: FinalDecisionType,
    val reasons: List<String>,
    // TODO: ai Score 구체화 필요
    val aiScore: Double?,
    val evaluatedAt: Instant,
    val ruleHits: List<RiskRuleHit>
) {

    init {
        if (aiScore != null) require(aiScore in 0.0..100.0) { "aiScore 범위는 0~100" }
    }

    companion object {
        fun create(
            txId: Long,
            decision: FinalDecisionType,
            reasons: List<String>,
            aiScore: Double?,
            ruleHits: List<RiskRuleHit>,
            evaluatedAt: Instant = Instant.now()
        ): RiskLog {
            return RiskLog(
                id = null,
                txId = txId,
                decision = decision,
                reasons = reasons.distinct().take(10), // 과도한 사유 제한
                aiScore = aiScore,
                evaluatedAt = evaluatedAt,
                ruleHits = ruleHits.toList()
            )
        }
    }
}