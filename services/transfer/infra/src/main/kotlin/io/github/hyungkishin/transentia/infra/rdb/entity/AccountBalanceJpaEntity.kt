package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.model.Currency
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.model.account.AccountBalance
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import jakarta.persistence.*
import org.hibernate.Hibernate

@Entity
@Table(name = "account_balances")
class AccountBalanceJpaEntity(

    @Id
    val id: Long,

    @Column(name = "account_number", nullable = false)
    val accountNumber: String,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    /**
     * DB에는 소수점 없는 정수(Long) 값 저장 (scale 반영된 raw value)
     */
    @Column(name = "balance", nullable = false)
    var balance: Long,

    /**
     * 통화 코드 (KRW, USD, ...)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false)
    val currency: Currency = Currency.KRW,

    ) : BaseEntity() {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    var user: UserJpaEntity? = null

    fun toDomain(): AccountBalance =
        AccountBalance.of(
            SnowFlakeId(id),
            SnowFlakeId(userId),
            accountNumber,
            Amount.fromMinor(balance, Currency.KRW),
        )

    companion object {
        fun from(domain: AccountBalance): AccountBalanceJpaEntity =
            AccountBalanceJpaEntity(
                id = domain.id.value,
                userId = domain.userId.value,
                balance = domain.current().minor,
                currency = domain.current().currency,
                accountNumber = domain.accountNumber,
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as AccountBalanceJpaEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
