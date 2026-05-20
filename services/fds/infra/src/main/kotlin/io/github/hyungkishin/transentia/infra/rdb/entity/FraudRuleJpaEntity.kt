package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.model.FraudeRule
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.Type

@Entity
@Table(name = "fraud_rules")
class FraudRuleJpaEntity(
    @Id
    @Column(nullable = false)
    val id: Long, // Snowflake ID를 직접 할당
    @Column(name = "rule_name", nullable = false, length = 100)
    val ruleName: String,
    @Column(name = "rule_type", nullable = false, length = 50)
    val ruleType: String,
    @Column(nullable = false)
    val weight: Long,
    @Type(JsonBinaryType::class)
    @Column(columnDefinition = "jsonb", nullable = false)
    val threshold: Map<String, Any>,
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
    @Column(nullable = false)
    val priority: Int = 0,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): FraudeRule =
        FraudeRule.of(
            SnowFlakeId(id),
            ruleName,
            ruleType,
            weight,
            threshold,
            isActive,
            priority,
            createdAt,
            updatedAt,
        )

    companion object {
        fun from(domain: FraudeRule): FraudRuleJpaEntity =
            FraudRuleJpaEntity(
                id = domain.id.value,
                ruleName = domain.ruleName,
                ruleType = domain.ruleType,
                weight = domain.weight,
                threshold = domain.threshold,
                isActive = domain.isActive,
                priority = domain.priority,
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FraudRuleJpaEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
