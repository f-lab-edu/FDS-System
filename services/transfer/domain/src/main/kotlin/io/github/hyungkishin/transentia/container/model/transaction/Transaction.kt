package io.github.hyungkishin.transentia.container.model.transaction

import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted
import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.enums.TransactionStatus
import java.time.Clock
import java.time.Instant

/**
 * 송금 트랜잭션 도메인 객체.
 *
 * 불변 객체. 상태 변경은 새 인스턴스 반환으로 표현한다.
 * complete()/fail() 가 기존 객체의 status 를 바꾸지 않는다 — 함수형 모델.
 *
 * 도메인 이벤트 발행은 complete() 가 반환하는 [Completion] 의 event 필드로 분리한다.
 * 호출처가 새 인스턴스를 저장하고 이벤트를 publish 한다.
 */
class Transaction private constructor(
    val id: SnowFlakeId,
    val senderId: SnowFlakeId,
    val receiverId: SnowFlakeId,
    val amount: Amount,
    val status: TransactionStatus,
    val createdAt: Instant,
    val failReason: String? = null,
) {
    /** 멱등 완료 — 새 인스턴스 + 발행할 이벤트. */
    fun complete(): Completion {
        check(status == TransactionStatus.PENDING) { "PENDING 상태만 완료할 수 있습니다." }
        return Completion(
            transaction = copyWith(status = TransactionStatus.COMPLETED),
            event = TransferCompleted(
                transactionId = id.value,
                senderUserId = senderId.value,
                receiverUserId = receiverId.value,
                amount = amount.money.rawValue,
                currency = amount.currency.name,
            ),
        )
    }

    /** 실패 처리 — 새 인스턴스 반환. */
    fun fail(reason: String): Transaction {
        check(status == TransactionStatus.PENDING) { "PENDING 상태만 실패 처리할 수 있습니다." }
        return copyWith(status = TransactionStatus.FAILED, failReason = reason)
    }

    fun isCompleted(): Boolean = status == TransactionStatus.COMPLETED
    fun isPending(): Boolean = status == TransactionStatus.PENDING
    fun isFailed(): Boolean = status == TransactionStatus.FAILED

    private fun copyWith(
        status: TransactionStatus = this.status,
        failReason: String? = this.failReason,
    ): Transaction = Transaction(
        id = id,
        senderId = senderId,
        receiverId = receiverId,
        amount = amount,
        status = status,
        createdAt = createdAt,
        failReason = failReason,
    )

    override fun equals(other: Any?): Boolean = other is Transaction && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String =
        "Transaction(id=$id, sender=$senderId, receiver=$receiverId, amount=$amount, status=$status)"

    /**
     * complete() 의 결과 묶음 — 새 Transaction 과 발행할 이벤트.
     */
    data class Completion(
        val transaction: Transaction,
        val event: TransferCompleted,
    )

    companion object {
        /** 신규 송금 생성. PENDING 상태로 시작. */
        fun create(
            id: SnowFlakeId,
            senderId: SnowFlakeId,
            receiverId: SnowFlakeId,
            amount: Amount,
            clock: Clock = Clock.systemUTC(),
        ): Transaction {
            require(senderId != receiverId) { "송신자와 수신자는 동일할 수 없습니다." }
            return Transaction(
                id = id,
                senderId = senderId,
                receiverId = receiverId,
                amount = amount,
                status = TransactionStatus.PENDING,
                createdAt = Instant.now(clock),
            )
        }

        /** DB 또는 이벤트 소싱 복원용. invariants 재검증 안 함. */
        fun reconstitute(
            id: SnowFlakeId,
            senderId: SnowFlakeId,
            receiverId: SnowFlakeId,
            amount: Amount,
            status: TransactionStatus,
            createdAt: Instant,
            failReason: String? = null,
        ): Transaction = Transaction(id, senderId, receiverId, amount, status, createdAt, failReason)

        /** 하위 호환 — 신규 호출은 create() 사용 권장. */
        @Deprecated("Use create()", ReplaceWith("Transaction.create(id, senderSnowFlakeId, receiverSnowFlakeId, amount, clock)"))
        fun of(
            id: SnowFlakeId,
            senderSnowFlakeId: SnowFlakeId,
            receiverSnowFlakeId: SnowFlakeId,
            amount: Amount,
            clock: Clock = Clock.systemUTC(),
        ): Transaction = create(id, senderSnowFlakeId, receiverSnowFlakeId, amount, clock)

        /** 하위 호환 — 신규 호출은 reconstitute() 사용 권장. */
        @Deprecated("Use reconstitute()", ReplaceWith("Transaction.reconstitute(id, senderSnowFlakeId, receiverSnowFlakeId, amount, status, createdAt, failReason)"))
        fun restored(
            id: SnowFlakeId,
            senderSnowFlakeId: SnowFlakeId,
            receiverSnowFlakeId: SnowFlakeId,
            amount: Amount,
            status: TransactionStatus,
            createdAt: Instant,
            failReason: String?,
        ): Transaction = reconstitute(id, senderSnowFlakeId, receiverSnowFlakeId, amount, status, createdAt, failReason)
    }
}
