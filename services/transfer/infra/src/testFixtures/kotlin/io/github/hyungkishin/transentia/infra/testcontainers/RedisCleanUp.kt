package io.github.hyungkishin.transentia.infra.testcontainers

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 테스트 간 Redis 상태 격리.
 * 각 테스트의 @BeforeEach 에서 호출하여 키 잔존을 막는다.
 */
@Component
class RedisCleanUp(
    private val redisTemplate: StringRedisTemplate,
) {
    fun all() {
        redisTemplate.execute<String> { it.serverCommands().flushAll(); "OK" }
    }
}
