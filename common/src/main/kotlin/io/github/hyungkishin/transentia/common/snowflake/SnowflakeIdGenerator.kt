package io.github.hyungkishin.transentia.common.snowflake

class SnowflakeIdGenerator(
    private val snowflake: Snowflake
) {
    fun generateTransferId(): TransferId = TransferId(snowflake.nextId())
    fun generateUserId(): UserId = UserId(snowflake.nextId())
}