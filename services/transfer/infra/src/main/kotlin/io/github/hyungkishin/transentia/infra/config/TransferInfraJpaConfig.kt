package io.github.hyungkishin.transentia.infra.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan(
    basePackages = [
        "io.github.hyungkishin.transentia.infra.rdb.entity"
    ]
)
@EnableJpaRepositories(
    basePackages = [
        "io.github.hyungkishin.transentia.infra.rdb.repository"
    ]
)
class TransferInfraJpaConfig
