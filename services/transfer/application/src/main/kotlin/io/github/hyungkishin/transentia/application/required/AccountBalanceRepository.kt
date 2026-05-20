package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.model.account.AccountBalance

interface AccountBalanceRepository {
    fun findByUserId(snowFlakeId: SnowFlakeId): AccountBalance?

    fun save(account: AccountBalance): AccountBalance
}
