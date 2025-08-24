package io.github.hyungkishin.transentia.common.snowflake

// TODO: 역으로 의존되는게 아닐까 ?
/** 강 타입 ID — 필요 도메인별로 추가 */
@JvmInline value class UserId(val value: Long)
@JvmInline value class TransferId(val value: Long)
@JvmInline value class TransactionId(val value: Long)