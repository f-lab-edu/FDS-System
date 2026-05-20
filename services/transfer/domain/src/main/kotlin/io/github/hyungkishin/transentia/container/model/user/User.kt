package io.github.hyungkishin.transentia.container.model.user

import io.github.hyungkishin.transentia.common.error.CommonError
import io.github.hyungkishin.transentia.common.error.DomainException
import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.model.Currency
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.enums.UserRole
import io.github.hyungkishin.transentia.container.enums.UserStatus
import io.github.hyungkishin.transentia.container.model.account.AccountBalance
import java.time.Instant

/**
 * 사용자 도메인 객체.
 *
 * 책임:
 *  - 송금 가능 여부에 대한 자가 검증 (assertCanSend / assertCanReceive).
 *  - predicate (canSend / canReceive / isBlacklisted) — 분기용.
 *  - 외부 도메인 룰 (블랙리스트, 일일 한도, 잔액) 을 호출처 if/throw 가
 *    아닌 자기 메서드로 표현.
 *
 * 변경 시 새 인스턴스 반환. 본 PR 에선 잠금/해제 같은 변형 메서드는
 * 추가하지 않고 자가 검증만 정리.
 */
class User private constructor(
    val id: SnowFlakeId,
    val name: UserName,
    val email: Email,
    val status: UserStatus,
    val role: UserRole,
    val accountBalance: AccountBalance,
    val isTransferLocked: Boolean,
    val transferLockReason: TransferLockReason?,
    val dailyTransferLimit: DailyTransferLimit,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    // ============ Predicate ============

    fun isBlacklisted(): Boolean = isTransferLocked || status == UserStatus.SUSPENDED

    fun canReceive(): Boolean = status == UserStatus.ACTIVE && !isTransferLocked

    fun canSend(
        amount: Amount,
        dailyAccumulated: Long = 0L,
    ): Boolean {
        if (isBlacklisted()) return false
        if (accountBalance.balance.currency != amount.currency) return false
        if (!accountBalance.hasEnoughBalance(amount)) return false
        val limitRaw = dailyTransferLimit.value * Currency.KRW.scaleFactor
        return (dailyAccumulated + amount.money.rawValue) <= limitRaw
    }

    // ============ Assertion ============

    /**
     * 송신자 자가 검증. 위반 시 DomainException 으로 fail-fast.
     * 어떤 룰을 어겼는지 메시지에 정확히 담는다.
     */
    fun assertCanSend(
        amount: Amount,
        dailyAccumulated: Long = 0L,
    ) {
        if (isBlacklisted()) {
            throw DomainException(
                CommonError.InvalidArgument("sender_blocked"),
                "송금이 제한된 사용자입니다: ${blockReason()}",
            )
        }
        if (accountBalance.balance.currency != amount.currency) {
            throw DomainException(
                CommonError.InvalidArgument("currency_mismatch"),
                "통화가 일치하지 않습니다: 잔액=${accountBalance.balance.currency}, 요청=${amount.currency}",
            )
        }
        val limitRaw = dailyTransferLimit.value * Currency.KRW.scaleFactor
        if ((dailyAccumulated + amount.money.rawValue) > limitRaw) {
            throw DomainException(
                CommonError.InvalidArgument("daily_limit_exceeded"),
                "일일 송금 한도($dailyTransferLimit)를 초과했습니다",
            )
        }
        if (!accountBalance.hasEnoughBalance(amount)) {
            throw DomainException(
                CommonError.InvalidArgument("insufficient_balance"),
                "잔액이 부족합니다. 현재 잔액=${accountBalance.balance}, 요청 금액=$amount",
            )
        }
    }

    fun assertCanReceive() {
        if (!canReceive()) {
            throw DomainException(
                CommonError.InvalidArgument("receiver_blocked"),
                "입금이 불가능한 계정입니다: ${blockReason()}",
            )
        }
        if (isBlacklisted()) {
            throw DomainException(
                CommonError.InvalidArgument("user_blocked"),
                "제한된 사용자입니다: ${blockReason()}",
            )
        }
    }

    // ============ Read-only utility ============

    fun blockReason(): String =
        when {
            status == UserStatus.SUSPENDED -> "계정이 정지되었습니다"
            status == UserStatus.DEACTIVATED -> "탈퇴한 계정입니다"
            isTransferLocked -> transferLockReason?.value ?: "송금이 제한되었습니다"
            else -> ""
        }

    @Deprecated("Use blockReason()", ReplaceWith("blockReason()"))
    fun getBlockReason(): String = blockReason()

    /** 하위 호환 — 신규 코드는 canSend(amount, dailyAccumulated) 권장. */
    @Deprecated("Use canSend(amount, dailyAccumulated)", ReplaceWith("canSend(amount, dailyAccumulated)"))
    fun validateTransferAmount(
        amount: Amount,
        dailyAccumulated: Long = 0L,
    ): Boolean = canSend(amount, dailyAccumulated)

    companion object {
        fun of(
            id: SnowFlakeId,
            name: UserName,
            email: Email,
            status: UserStatus,
            role: UserRole,
            accountBalance: AccountBalance,
            isTransferLocked: Boolean,
            transferLockReason: TransferLockReason?,
            dailyTransferLimit: DailyTransferLimit,
            createdAt: Instant,
            updatedAt: Instant,
        ): User =
            User(
                id,
                name,
                email,
                status,
                role,
                accountBalance,
                isTransferLocked,
                transferLockReason,
                dailyTransferLimit,
                createdAt,
                updatedAt,
            )
    }
}
