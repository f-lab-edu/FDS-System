package io.github.hyungkishin.transentia.infra.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EntityScan("io.github.hyungkishin.transentia.infra.rdb.entity")
@EnableJpaRepositories("io.github.hyungkishin.transentia.infra.rdb.repository")
class TransferInfraJpaConfig