package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.SuspiciousPatternAlertJpaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface SuspiciousPatternAlertJpaRepository : JpaRepository<SuspiciousPatternAlertJpaEntity, Long>
