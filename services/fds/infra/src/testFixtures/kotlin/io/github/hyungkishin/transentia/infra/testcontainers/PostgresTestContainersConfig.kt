package io.github.hyungkishin.transentia.infra.testcontainers

import org.springframework.context.annotation.Configuration
import org.testcontainers.containers.PostgreSQLContainer

/**
 * PostgreSQL Testcontainers 공유 설정.
 * JVM 1회 부팅 후 모든 통합 테스트가 같은 컨테이너 재사용.
 * test-schema.sql 로 transactions / fraud_detections / suspicious_pattern_alerts 초기화.
 */
@Configuration
class PostgresTestContainersConfig {
    companion object {
        @Suppress("HttpUrlsUsage")
        private val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("transfer_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("test-schema.sql")
            .apply { start() }

        init {
            System.setProperty("spring.datasource.url", container.jdbcUrl)
            System.setProperty("spring.datasource.username", container.username)
            System.setProperty("spring.datasource.password", container.password)
            System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver")
            System.setProperty("spring.jpa.hibernate.ddl-auto", "none")
            System.setProperty("spring.flyway.enabled", "false")
        }
    }
}
