package io.github.hyungkishin.transentia.api.performance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * RestTemplate 성능 테스트를 위한 독립 실행 애플리케이션
 */
@SpringBootApplication
@Import(RestTemplateConfig::class)
class PerformanceTestApplication

fun main(args: Array<String>) {
    runApplication<PerformanceTestApplication>(*args) {
        setAdditionalProfiles("performance-test")
    }
}
