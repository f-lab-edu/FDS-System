package io.github.hyungkishin.transentia.relay.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox Relay 설정
 */
@ConfigurationProperties(prefix = "app.outbox.relay")
data class OutboxRelayConfig(

    /** DB 조회 배치 크기 */
    val chunkSize: Int = 100,

    /** 스케줄링 간격 (ms) */
    val fixedDelayMs: Long = 1000,

    /** 첫 실행 지연 시간 (ms) */
    val initialDelayMs: Long = 5000,

    /** Worker 스레드 개수 */
    val threadPoolSize: Int = Runtime.getRuntime().availableProcessors() * 2,

    /** Worker 타임아웃 (초) */
    val timeoutSeconds: Long = 5,

    /** 첫 재시도 백오프 시간 (ms) - 지수 증가 */
    val baseBackoffMs: Long = 5000,

    /** Stuck SENDING 판단 기준 (초) */
    val sendingTimeoutSeconds: Long = 120,

    /** 느린 처리 경고 임계값 (ms) */
    val slowProcessingThresholdMs: Long = 3000
)
