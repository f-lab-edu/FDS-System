package io.github.hyungkishin.transentia.application.port.out

import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.domain.model.AccountBalance

interface AccountBalanceRepository {
    fun findByUserId(userId: UserId): AccountBalance?
    fun save(account: AccountBalance): AccountBalance
}
