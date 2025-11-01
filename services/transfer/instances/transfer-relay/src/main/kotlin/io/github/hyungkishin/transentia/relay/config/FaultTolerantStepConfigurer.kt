package io.github.hyungkishin.transentia.relay.config

import io.github.hyungkishin.transentia.relay.exception.InvalidEventDataException
import io.github.hyungkishin.transentia.relay.exception.NonRetryableKafkaException
import io.github.hyungkishin.transentia.relay.exception.RetryableKafkaException
import org.springframework.batch.core.step.builder.FaultTolerantStepBuilder
import org.springframework.retry.backoff.ExponentialBackOffPolicy
import org.springframework.stereotype.Component
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Spring Batch FaultTolerant 정책 설정
 *
 * 역할
 * - Retry / Skip / NoRetry 정책을 한 곳에서 관리
 * - Step 설정 로직에서 예외 정책 분리
 * - 재사용 가능한 정책 컴포넌트
 *
 * 예외 분류
 * - Retry: 일시적 네트워크 오류 (재시도 가능)
 * - Skip: 데이터 오류 (복구 불가, DLQ 이동)
 * - NoRetry / NoSkip: 코드 버그 (즉시 실패)
 */
@Component
class FaultTolerantStepConfigurer(
    private val config: OutboxRelayConfig
) {

    /**
     * FaultTolerant 정책 적용
     *
     * @param builder Step의 FaultTolerantStepBuilder
     * @return 정책이 적용된 builder
     */
    fun <I, O> configure(
        builder: FaultTolerantStepBuilder<I, O>
    ): FaultTolerantStepBuilder<I, O> {

        return builder.apply {
            // Retry 정책
            configureRetryPolicy()

            // Skip 정책
            configureSkipPolicy()

            // NoRetry / NoSkip 정책
            configureNoRetryPolicy()
        }

    }

    /**
     * Retry 정책 설정
     *
     * - 일시적 오류는 지수 백오프로 재시도
     * - maxAttempts 초과 시 skip 정책으로 이동
     */
    private fun <I, O> FaultTolerantStepBuilder<I, O>.configureRetryPolicy() {
        // 재시도 가능한 예외
        retry(RetryableKafkaException::class.java)
        retry(TimeoutException::class.java)
        retry(SocketTimeoutException::class.java)
        
        // 최대 재시도 횟수
        retryLimit(config.maxAttempts)
        
        // 지수 백오프 정책
        backOffPolicy(exponentialBackOffPolicy())
    }

    /**
     * Skip 정책 설정
     *
     * - 복구 불가능한 데이터 오류
     * - SkipListener에서 DLQ로 이동됨
     */
    private fun <I, O> FaultTolerantStepBuilder<I, O>.configureSkipPolicy() {
        skip(NonRetryableKafkaException::class.java)
        skip(InvalidEventDataException::class.java)

        // Chunk 크기만큼 허용
        skipLimit(config.chunkSize)
    }

    /**
     * NoRetry / NoSkip 정책 설정
     *
     * - 코드 버그는 즉시 Job 실패
     * - 빠른 피드백으로 수정 유도
     */
    private fun <I, O> FaultTolerantStepBuilder<I, O>.configureNoRetryPolicy() {
        noSkip(NullPointerException::class.java)
        noSkip(IllegalArgumentException::class.java)
        noSkip(IllegalStateException::class.java)

        noRetry(NullPointerException::class.java)
        noRetry(IllegalArgumentException::class.java)
    }

    /**
     * 지수 백오프 정책
     *
     * - 초기 대기: baseBackoffMs
     * - 배수: 2배
     * - 최대 대기: 60초
     */
    private fun exponentialBackOffPolicy(): ExponentialBackOffPolicy {
        return ExponentialBackOffPolicy().apply {
            initialInterval = config.baseBackoffMs
            multiplier = 2.0
            maxInterval = 60000  // 60초
        }
    }

}
