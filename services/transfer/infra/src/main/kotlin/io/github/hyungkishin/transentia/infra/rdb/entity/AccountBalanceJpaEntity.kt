package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.model.AccountBalance
import io.github.hyungkishin.transentia.consumer.model.Money
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import jakarta.persistence.*
import org.hibernate.Hibernate
import org.springframework.data.domain.Persistable

@Entity
@Table(name = "account_balances")
class AccountBalanceJpaEntity(

    @Id
    @Column(name = "user_id")
    val userId: Long,

    @Column(nullable = false)
    var balance: Long,

    @Version
    var version: Long? = null

) : BaseEntity(), Persistable<Long> {

    override fun getId(): Long = userId

    // Snowflake 사전 ID 여도 version==null 이면 persist(신규), 아니면 merge(기존)로 명확히 분기
    override fun isNew(): Boolean = (version == null)

    fun toDomain(): AccountBalance =
        AccountBalance.initialize(
            UserId(userId),
            Money.fromRawValue(balance)
        )

    companion object {
        fun from(domain: AccountBalance, currentVersion: Long? = null): AccountBalanceJpaEntity =
            AccountBalanceJpaEntity(
                userId = domain.userId.value,
                balance = domain.current().rawValue,
                version = currentVersion
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as AccountBalanceJpaEntity
        return this.userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}
