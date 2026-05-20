package io.github.hyungkishin.transentia.infra.redis

import io.github.hyungkishin.transentia.infra.testcontainers.RedisCleanUp
import io.github.hyungkishin.transentia.infra.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

@SpringBootTest(classes = [RedisDailyTransferAmountCacheAdapterIntegrationTest.TestConfig::class])
@Import(RedisTestContainersConfig::class)
@DisplayName("RedisDailyTransferAmountCacheAdapter 통합 테스트")
class RedisDailyTransferAmountCacheAdapterIntegrationTest {
    @SpringBootApplication(scanBasePackageClasses = [RedisDailyTransferAmountCacheAdapter::class])
    class TestConfig {
        @org.springframework.context.annotation.Bean
        fun connectionFactory(
            @org.springframework.beans.factory.annotation.Value("\${spring.data.redis.host}") host: String,
            @org.springframework.beans.factory.annotation.Value("\${spring.data.redis.port}") port: Int,
        ): LettuceConnectionFactory = LettuceConnectionFactory(host, port)

        @org.springframework.context.annotation.Bean
        fun stringRedisTemplate(connectionFactory: LettuceConnectionFactory): StringRedisTemplate = StringRedisTemplate(connectionFactory)

        @org.springframework.context.annotation.Bean
        fun cleanUp(template: StringRedisTemplate) = RedisCleanUp(template)
    }

    @Autowired lateinit var adapter: RedisDailyTransferAmountCacheAdapter

    @Autowired lateinit var template: StringRedisTemplate

    @Autowired lateinit var cleanUp: RedisCleanUp

    @BeforeEach
    fun reset() = cleanUp.all()

    @Test
    fun `최초 조회 시 0 을 반환한다`() {
        assertThat(adapter.getTodayAmount(1001L)).isZero()
    }

    @Test
    fun `addTodayAmount 가 누적치를 증가시키고 갱신된 값을 반환한다`() {
        val first = adapter.addTodayAmount(1001L, 10_000L)
        val second = adapter.addTodayAmount(1001L, 25_000L)

        assertThat(first).isEqualTo(10_000L)
        assertThat(second).isEqualTo(35_000L)
        assertThat(adapter.getTodayAmount(1001L)).isEqualTo(35_000L)
    }

    @Test
    fun `다른 사용자 키는 격리된다`() {
        adapter.addTodayAmount(1001L, 10_000L)
        adapter.addTodayAmount(1002L, 50_000L)

        assertThat(adapter.getTodayAmount(1001L)).isEqualTo(10_000L)
        assertThat(adapter.getTodayAmount(1002L)).isEqualTo(50_000L)
    }

    @Test
    fun `addTodayAmount 호출 시 TTL 이 26 시간 이내로 설정된다`() {
        adapter.addTodayAmount(1001L, 10_000L)

        val keys = template.keys("daily:transfer:1001:*")
        assertThat(keys).hasSize(1)
        val ttlSeconds = template.getExpire(keys.first())
        assertThat(ttlSeconds).isGreaterThan(0L).isLessThanOrEqualTo(93_600L)
    }
}
