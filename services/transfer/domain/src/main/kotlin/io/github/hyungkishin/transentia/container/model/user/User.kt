package io.github.hyungkishin.transentia.container.model.user

import io.github.hyungkishin.transentia.container.model.account.AccountBalance
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.enums.UserRole
import io.github.hyungkishin.transentia.container.enums.UserStatus
import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.model.Currency
import java.time.Instant

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
    val updatedAt: Instant
) {

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
            updatedAt: Instant
        ): User {
            return User(
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
                updatedAt
            )
        }
    }

    /** 블랙리스트 여부 판단 */
    fun isBlacklisted(): Boolean =
        isTransferLocked || status == UserStatus.SUSPENDED

    /** 송금 가능 여부 체크 */
    fun canReceive(): Boolean =
        status == UserStatus.ACTIVE && !isTransferLocked

    /**
     * 송금 금액 검증 (일일 한도). dailyAccumulated 는 외부 캐시에서 조회한 오늘 누적 송금액(rawValue).
     */
    fun validateTransferAmount(amount: Amount, dailyAccumulated: Long = 0L): Boolean {
        if (accountBalance.balance.currency != amount.currency) {
            return false
        }
        val limitRaw = dailyTransferLimit.value * Currency.KRW.scaleFactor
        return (dailyAccumulated + amount.money.rawValue) <= limitRaw
    }

    /** 차단 사유 반환 */
    fun getBlockReason(): String =
        when {
            status == UserStatus.SUSPENDED -> "계정이 정지되었습니다"
            status == UserStatus.DEACTIVATED -> "탈퇴한 계정입니다"
            isTransferLocked -> transferLockReason?.value ?: "송금이 제한되었습니다"
            else -> ""
        }
}
