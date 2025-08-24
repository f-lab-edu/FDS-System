package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.port.out.AccountBalanceRepository
import io.github.hyungkishin.transentia.shared.snowflake.UserId
import io.github.hyungkishin.transentia.domain.model.AccountBalance
import io.github.hyungkishin.transentia.infra.rdb.entity.AccountBalanceJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.AccountBalanceJpaRepository
import org.springframework.stereotype.Component

@Component
class AccountBalancePersistenceAdapter(
    private val jpaRepository: AccountBalanceJpaRepository
) : AccountBalanceRepository {

    override fun findByUserId(userId: UserId): AccountBalance? =
        jpaRepository.findById(userId.value).orElse(null)?.toDomain()

    override fun save(account: AccountBalance): AccountBalance =
        jpaRepository.save(AccountBalanceJpaEntity.from(account)).toDomain()

}