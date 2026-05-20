package io.github.hyungkishin.transentia.container.model

import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import java.time.Instant

class FraudeRule private constructor(
    val id: SnowFlakeId,
    val ruleName: String,
    val ruleType: String,
    val weight: Long,
    val threshold: Map<String, Any>,
    val isActive: Boolean,
    val priority: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        fun of(
            id: SnowFlakeId,
            ruleName: String,
            ruleType: String,
            weight: Long,
            threshold: Map<String, Any>,
            isActive: Boolean,
            priority: Int,
            createdAt: Instant,
            updatedAt: Instant,
        ): FraudeRule = FraudeRule(id, ruleName, ruleType, weight, threshold, isActive, priority, createdAt, updatedAt)
    }
}
