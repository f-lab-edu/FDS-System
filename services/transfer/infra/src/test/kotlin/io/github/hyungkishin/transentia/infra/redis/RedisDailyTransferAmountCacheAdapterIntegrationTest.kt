package io.github.hyungkishin.transentia.infra.redis

import io.github.hyungkishin.transentia.infra.support.RedisIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

@SpringBootTest(classes = [RedisDailyTransferAmountCacheAdapterIntegrationTest.TestConfig::class])
@DisplayName("RedisDailyTransferAmountCacheAdapter 통합 테스트")
class RedisDailyTransferAmountCacheAdapterIntegrationTest : RedisIntegrationTestBase() {

    @ComponentScan(
        basePackageClasses = [RedisDailyTransferAmountCacheAdapter::class]
    )
    @org.springframework.boot.autoconfigure.SpringBootApplication
    class TestConfig {
        @org.springframework.context.annotation.Bean
        fun connectionFactory(): LettuceConnectionFactory =
            LettuceConnectionFactory(redis.host, redis.firstMappedPort)

        @org.springframework.context.annotation.Bean
        fun stringRedisTemplate(connectionFactory: LettuceConnectionFactory): StringRedisTemplate =
            StringRedisTemplate(connectionFactory)
    }

    @Autowired
    lateinit var adapter: RedisDailyTransferAmountCacheAdapter

    @Autowired
    lateinit var template: StringRedisTemplate

    @BeforeEach
    fun flushAll() {
        template.execute<String> { it.serverCommands().flushAll(); "OK" }
    }

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

        // KST 기준 daily 키 이름은 어댑터 내부 구현이지만, 키 패턴을 검증한다.
        val keys = template.keys("daily:transfer:1001:*")
        assertThat(keys).hasSize(1)
        val ttlSeconds = template.getExpire(keys.first())
        // 26h = 93600 sec. 0보다 크고 93600 이하여야 한다.
        assertThat(ttlSeconds).isGreaterThan(0L).isLessThanOrEqualTo(93_600L)
    }
}
