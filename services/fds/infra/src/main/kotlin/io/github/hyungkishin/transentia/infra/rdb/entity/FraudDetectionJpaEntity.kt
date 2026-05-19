package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.infra.constants.ActionType
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import jakarta.persistence.*
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(
    name = "fraud_detections",
    indexes = [
        Index(name = "idx_fraud_detections_event_id", columnList = "event_id"),
        Index(name = "idx_fraud_detections_from_account", columnList = "from_account_id, detected_at"),
        Index(name = "idx_fraud_detections_action", columnList = "action_type, detected_at")
    ]
)
class FraudDetectionJpaEntity(

    @Id
    @Column(nullable = false)
    val id: Long,  // Snowflake ID

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "from_account_id", nullable = false)
    val fromAccountId: Long,  // Snowflake ID

    @Column(name = "to_account_id", nullable = false)
    val toAccountId: Long,    // Snowflake ID

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 10)
    val currency: String,

    @Column(name = "total_risk_score", nullable = false)
    val totalRiskScore: Int,

    @Column(name = "action_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val actionType: ActionType,

    @Type(JsonBinaryType::class)
    @Column(name = "rule_results", columnDefinition = "jsonb", nullable = false)
    val ruleResults: List<Map<String, Any>>,

    @Column(name = "detected_at", nullable = false)
    val detectedAt: Instant,

    @Column(name = "trace_id", length = 255)
    val traceId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FraudDetectionJpaEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

}