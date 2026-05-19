package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.infra.support.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit

@SpringBootTest(
    classes = [JpaRecentTransferCountQueryAdapterIntegrationTest.TestApp::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"]
)
@DisplayName("JpaRecentTransferCountQueryAdapter 통합 테스트")
class JpaRecentTransferCountQueryAdapterIntegrationTest : PostgresIntegrationTestBase() {

    @SpringBootApplication(
        scanBasePackages = [
            "io.github.hyungkishin.transentia.infra.rdb.adapter",
            "io.github.hyungkishin.transentia.infra.rdb.repository",
            "io.github.hyungkishin.transentia.infra.rdb.entity",
        ]
    )
    class TestApp

    @Autowired
    lateinit var adapter: JpaRecentTransferCountQueryAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun cleanup() {
        jdbc.update("TRUNCATE TABLE transactions")
    }

    private fun insertTx(id: Long, senderId: Long, createdAt: Instant) {
        jdbc.update(
            """
            INSERT INTO transactions
                (id, sender_user_id, receiver_user_id, amount, currency, status, created_at)
            VALUES (?, ?, ?, ?, 'KRW', 'COMPLETED', ?)
            """.trimIndent(),
            id, senderId, senderId + 1, 10_000L, java.sql.Timestamp.from(createdAt)
        )
    }

    @Test
    fun `since 이후의 송금 건수만 카운트한다`() {
        val now = Instant.now()
        insertTx(1, 1001L, now.minus(2, ChronoUnit.MINUTES))
        insertTx(2, 1001L, now.minus(8, ChronoUnit.MINUTES))
        insertTx(3, 1001L, now.minus(20, ChronoUnit.MINUTES)) // 윈도우 밖
        insertTx(4, 1002L, now.minus(1, ChronoUnit.MINUTES))  // 다른 사용자

        val count = adapter.countByUserSince(1001L, now.minus(10, ChronoUnit.MINUTES))

        assertThat(count).isEqualTo(2L)
    }

    @Test
    fun `데이터가 없으면 0 을 반환한다`() {
        assertThat(adapter.countByUserSince(9999L, Instant.now())).isZero()
    }

    @Test
    fun `다른 사용자의 거래는 카운트하지 않는다`() {
        val now = Instant.now()
        insertTx(10, 2001L, now.minus(1, ChronoUnit.MINUTES))
        insertTx(11, 2001L, now.minus(2, ChronoUnit.MINUTES))
        insertTx(12, 2002L, now.minus(1, ChronoUnit.MINUTES))

        assertThat(adapter.countByUserSince(2001L, now.minus(5, ChronoUnit.MINUTES))).isEqualTo(2L)
        assertThat(adapter.countByUserSince(2002L, now.minus(5, ChronoUnit.MINUTES))).isEqualTo(1L)
    }
}
