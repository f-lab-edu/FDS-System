package io.github.hyungkishin.transentia.infra.rdb.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "suspicious_pattern_alerts",
    indexes = [
        Index(name = "idx_alerts_account_detected", columnList = "account_id, detected_at DESC"),
        Index(name = "idx_alerts_detected", columnList = "detected_at DESC"),
    ],
)
class SuspiciousPatternAlertJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    @Column(name = "transfer_count", nullable = false)
    val transferCount: Int,
    @Column(name = "total_amount", nullable = false)
    val totalAmount: Long,
    @Column(name = "window_minutes", nullable = false)
    val windowMinutes: Long,
    @Column(name = "last_event_id", length = 64)
    val lastEventId: String? = null,
    @Column(name = "reason", nullable = false, length = 500)
    val reason: String,
    @Column(name = "detected_at", nullable = false)
    val detectedAt: Instant,
    @Column(name = "trace_id", length = 255)
    val traceId: String? = null,
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    val rawPayload: String? = null,
)
