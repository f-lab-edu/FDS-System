package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.FraudSettingJpaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface FraudSettingJpaRepository : JpaRepository<FraudSettingJpaEntity, Long>
