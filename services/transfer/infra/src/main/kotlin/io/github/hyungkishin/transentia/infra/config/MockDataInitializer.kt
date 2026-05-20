package io.github.hyungkishin.transentia.infra.config

import io.github.hyungkishin.transentia.application.required.UserRepository
import io.github.hyungkishin.transentia.common.model.Amount
import io.github.hyungkishin.transentia.common.model.Currency
import io.github.hyungkishin.transentia.common.snowflake.SnowFlakeId
import io.github.hyungkishin.transentia.container.enums.UserRole
import io.github.hyungkishin.transentia.container.enums.UserStatus
import io.github.hyungkishin.transentia.container.model.account.*
import io.github.hyungkishin.transentia.container.model.user.*
import java.time.Instant
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class MockDataInitializer(
    private val userRepository: UserRepository,
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        if (userRepository.findById(10001) == null) {
            val users = mutableListOf<User>()

            // 송금자 20명 생성 (10001 ~ 10020)
            for (i in 1..20) {
                val userId = 10000L + i
                val accountId = 20000L + i

                val account =
                    AccountBalance.of(
                        SnowFlakeId(accountId),
                        SnowFlakeId(userId),
                        "110-100-${String.format("%06d", i)}",
                        Amount.parse("1000000000", Currency.KRW), // 10억원 (부하 테스트용)
                    )

                val user =
                    User.of(
                        id = SnowFlakeId(userId),
                        name = UserName("송금자$i"),
                        email = Email("sender$i@test.com"),
                        status = UserStatus.ACTIVE,
                        role = UserRole.USER,
                        accountBalance = account,
                        isTransferLocked = false,
                        transferLockReason = null,
                        dailyTransferLimit = DailyTransferLimit.basic(),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                users.add(user)
            }

            // 수신자 20명 생성 (10021 ~ 10040)
            for (i in 21..40) {
                val userId = 10000L + i
                val accountId = 20000L + i

                val account =
                    AccountBalance.of(
                        SnowFlakeId(accountId),
                        SnowFlakeId(userId),
                        "110-200-${String.format("%06d", i)}",
                        Amount.parse("1000000000", Currency.KRW), // 10억원 (부하 테스트용)
                    )

                val user =
                    User.of(
                        id = SnowFlakeId(userId),
                        name = UserName("수신자$i"),
                        email = Email("receiver$i@test.com"),
                        status = UserStatus.ACTIVE,
                        role = UserRole.USER,
                        accountBalance = account,
                        isTransferLocked = false,
                        transferLockReason = null,
                        dailyTransferLimit = DailyTransferLimit.basic(),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                users.add(user)
            }

            // 일괄 저장
            users.forEach { userRepository.save(it) }

            println("Mock 사용자 데이터 초기화 완료")
            println("- 송금자 20명 (10001-10020): 각 10억원")
            println("- 수신자 20명 (10021-10040): 각 10억원")
        }
    }
}
