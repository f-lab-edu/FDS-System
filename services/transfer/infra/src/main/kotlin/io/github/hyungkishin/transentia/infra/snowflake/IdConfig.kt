package io.github.hyungkishin.transentia.infra.snowflake

import io.github.hyungkishin.transentia.common.snowflake.IdGenerator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(SnowflakeProps::class)
class IdConfig {

    @Bean
    fun idGenerator(p: SnowflakeProps, resolver: SnowflakeNodeIdResolver): IdGenerator {
        val nodeId = if (p.nodeId > 0) p.nodeId else resolver.resolve()
        val sf = Snowflake(
            nodeId = nodeId,
            customEpoch = p.customEpoch,
            maxClockBackwardMs = p.maxClockBackwardMs
        )
        return IdGenerator { sf.nextId() }
    }
}