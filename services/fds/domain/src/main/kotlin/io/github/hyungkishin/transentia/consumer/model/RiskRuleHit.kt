package io.github.hyungkishin.transentia.consumer.model

import io.github.hyungkishin.transentia.consumer.enums.RuleSeverity
import java.time.Instant

/**
 * 룰 평가 결과(1건). 도메인 순수 VO.
 */
data class RiskRuleHit(
    val txId: Long,
    val ruleCode: String,
    val severity: RuleSeverity,
    val hit: Boolean = true,
    val score: Double? = null,    // 룰별 점수(선택)
    val weight: Int = 1,          // 룰 가중치(선택)
    val reason: String? = null,
    val occurredAt: Instant = Instant.now()
) {
    init {
        require(ruleCode.isNotBlank()) { "ruleCode 는 비어있을 수 없습니다." }
        if (score != null) require(score in 0.0..100.0) { "score 범위는 0~100" }
        require(weight >= 0) { "weight는 음수 불가" }
    }
}