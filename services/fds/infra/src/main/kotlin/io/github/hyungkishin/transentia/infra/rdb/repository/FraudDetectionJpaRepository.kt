package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.FraudDetectionJpaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface FraudDetectionJpaRepository : JpaRepository<FraudDetectionJpaEntity, Long>
