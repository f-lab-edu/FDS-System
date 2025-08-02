package io.github.hyungkishin.transentia.common.snowflake

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "snowflake")
data class SnowflakeProperties(val nodeId: Long)

@Configuration
@EnableConfigurationProperties(SnowflakeProperties::class)
class SnowflakeConfig {
    @Bean
    fun snowflake(props: SnowflakeProperties): Snowflake = Snowflake(props.nodeId)

    @Bean
    fun snowflakeIdGenerator(snowflake: Snowflake): SnowflakeIdGenerator = SnowflakeIdGenerator(snowflake)
}
