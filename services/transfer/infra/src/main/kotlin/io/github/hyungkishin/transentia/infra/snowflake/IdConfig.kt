package io.github.hyungkishin.transentia.infra.snowflake

import io.github.hyungkishin.transentia.shared.snowflake.IdGenerator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(SnowflakeProps::class)
class IdConfig {

    @Bean
    fun idGenerator(p: SnowflakeProps): IdGenerator {
        val sf = Snowflake(
            nodeId = p.nodeId,
            customEpoch = p.customEpoch,
            maxClockBackwardMs = p.maxClockBackwardMs
        )
        return IdGenerator { sf.nextId() }
    }
}