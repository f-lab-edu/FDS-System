package io.github.hyungkishin.transentia.infra.testcontainers

import com.redis.testcontainers.RedisContainer
import org.springframework.context.annotation.Configuration
import org.testcontainers.utility.DockerImageName

/**
 * Redis Testcontainers 공유 설정.
 * @SpringBootTest 가 컴포넌트 스캔으로 자동 발견하고, JVM 1회 부팅한 컨테이너를
 * 모든 통합 테스트가 재사용한다. System.setProperty 로 동적 주입 — @DynamicPropertySource
 * 없이도 Spring 환경이 알아서 픽업.
 */
@Configuration
class RedisTestContainersConfig {
    companion object {
        private val redisContainer: RedisContainer =
            RedisContainer(DockerImageName.parse("redis:7-alpine"))
                .apply { start() }

        init {
            System.setProperty("spring.data.redis.host", redisContainer.host)
            System.setProperty("spring.data.redis.port", redisContainer.firstMappedPort.toString())
        }
    }
}
