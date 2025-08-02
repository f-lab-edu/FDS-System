package io.github.hyungkishin.transentia.infra.rdb.entity

import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.domain.model.AccountBalance
import io.github.hyungkishin.transentia.domain.model.Money
import io.github.hyungkishin.transentia.infra.config.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "account_balances")
class AccountBalanceJpaEntity(

    @Id
    val userId: Long,

    @Column(nullable = false)
    val balance: Long

): BaseEntity() {
    fun toDomain(): AccountBalance =
        AccountBalance.initialize(UserId(userId), Money.fromRawValue(balance))

    companion object {
        fun from(domain: AccountBalance): AccountBalanceJpaEntity =
            AccountBalanceJpaEntity(
                userId = domain.userId.value,
                balance = domain.current().rawValue
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccountBalanceJpaEntity) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()

}
