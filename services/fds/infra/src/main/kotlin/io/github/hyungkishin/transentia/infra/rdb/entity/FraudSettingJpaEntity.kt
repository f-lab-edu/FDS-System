package io.github.hyungkishin.transentia.infra.rdb.entity

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import jakarta.persistence.*
import java.time.Instant
import org.hibernate.annotations.Type

@Entity
@Table(name = "fraud_settings")
class FraudSettingJpaEntity(
    @Id
    @Column(nullable = false, length = 100)
    val key: String,
    @Type(JsonBinaryType::class)
    @Column(columnDefinition = "jsonb", nullable = false)
    val value: Map<String, Any>,
    @Column(columnDefinition = "TEXT")
    val description: String? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FraudSettingJpaEntity) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()
}
