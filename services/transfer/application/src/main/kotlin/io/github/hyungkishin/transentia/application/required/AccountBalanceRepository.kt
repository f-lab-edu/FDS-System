package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.common.snowflake.UserId
import io.github.hyungkishin.transentia.consumer.model.AccountBalance

interface AccountBalanceRepository {
    fun findByUserId(userId: UserId): AccountBalance?
    fun save(account: AccountBalance): AccountBalance
}
