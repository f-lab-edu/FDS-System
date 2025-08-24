package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.TransactionHistoryJpaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionHistoryJpaRepository : JpaRepository<TransactionHistoryJpaEntity, Long>