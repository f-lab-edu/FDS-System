package io.github.hyungkishin.transentia.shared.kernel.id

/** 새 ID를 만드는 최소 계약 */
fun interface IdGenerator<T> { fun newId(): T }

/** 강 타입 ID — 필요 도메인별로 추가 */
@JvmInline value class UserId(val value: Long)
@JvmInline value class TransferId(val value: Long)
@JvmInline value class TransactionId(val value: Long)