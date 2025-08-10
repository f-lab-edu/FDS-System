package io.github.hyungkishin.transentia.infra.snowflake

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "id.snowflake")
data class SnowflakeProps(
    var nodeId: Long = 0,
    var customEpoch: Long = 1704067200000L,
    var maxClockBackwardMs: Long = 5
)