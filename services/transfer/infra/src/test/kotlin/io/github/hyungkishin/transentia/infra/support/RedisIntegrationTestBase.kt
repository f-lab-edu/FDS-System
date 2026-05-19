package io.github.hyungkishin.transentia.infra.support

import com.redis.testcontainers.RedisContainer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Redis 통합 테스트용 베이스. 컨테이너는 JVM 단위로 한 번만 띄운다(static field 캐싱).
 */
@Testcontainers
abstract class RedisIntegrationTestBase {

    companion object {
        @Container
        @JvmStatic
        val redis: RedisContainer = RedisContainer(DockerImageName.parse("redis:7-alpine"))
            .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun redisProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.firstMappedPort.toString() }
        }
    }
}
