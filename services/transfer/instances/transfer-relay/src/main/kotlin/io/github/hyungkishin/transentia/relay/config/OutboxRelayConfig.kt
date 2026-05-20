package io.github.hyungkishin.transentia.relay.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox Relay 설정
 *
 * Phase 1: 단일 인스턴스 + 멀티스레드
 */
@ConfigurationProperties(prefix = "app.outbox.relay")
data class OutboxRelayConfig(
    /** DB 조회 배치 크기 */
    val chunkSize: Int = 500,
    /**
     * Worker 스레드 개수
     *
     * 결정 기준
     * - 처리 속도 목표
     * - 부하 테스트로 최종 결정
     */
    val threadPoolSize: Int = 3,
    /** Worker 타임아웃 (초) */
    val timeoutSeconds: Long = 30,
    /** 최대 재시도 횟수 (초과 시 DLQ) */
    val maxAttempts: Int = 5,
    /** 첫 재시도 백오프 시간 (ms) - 지수 증가 */
    val baseBackoffMs: Long = 5000,
    /** 사용 안 함 (호환성 유지) */
    val sendingTimeoutSeconds: Long = 300,
)
