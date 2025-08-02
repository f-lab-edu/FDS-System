package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.AccountBalanceJpaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface AccountBalanceJpaRepository : JpaRepository<AccountBalanceJpaEntity, Long>