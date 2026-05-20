package io.github.hyungkishin.transentia.infra.redis

import io.github.hyungkishin.transentia.application.required.DailyTransferAmountCachePort
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis 기반 일일 송금 누적 캐시 어댑터.
 *
 * Resilience 전략:
 * - @CircuitBreaker (name=redis) — half-open 단계에서 회복 시도.
 * - @Retry (name=redis) — 일시적 네트워크 지터에 대한 최대 2회 재시도.
 * - fallback — Redis 장애 시 안전한 값(누적 0) 반환. 일일 한도 검증이
 *   over-permissive 가 될 수 있으므로 fallback 호출은 메트릭으로 노출.
 *
 * NOTE: fallback 의 "0 반환" 은 fail-open 정책. 운영 요건상 fail-closed
 *       (Redis 죽으면 송금 거절) 가 필요하다면 fallback 에서 예외 던지도록 교체.
 */
@Component
class RedisDailyTransferAmountCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    meterRegistry: MeterRegistry,
) : DailyTransferAmountCachePort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val fallbackCounter: Counter =
        Counter
            .builder("redis.daily_limit.fallback")
            .description("Redis 일일 한도 캐시 fallback 호출 횟수 (CB open / 예외)")
            .register(meterRegistry)

    @CircuitBreaker(name = "redis", fallbackMethod = "getTodayAmountFallback")
    @Retry(name = "redis")
    override fun getTodayAmount(userId: Long): Long {
        val value = redisTemplate.opsForValue().get(keyFor(userId)) ?: return 0L
        return value.toLongOrNull() ?: 0L
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "addTodayAmountFallback")
    @Retry(name = "redis")
    override fun addTodayAmount(
        userId: Long,
        amount: Long,
    ): Long {
        val key = keyFor(userId)
        val ops = redisTemplate.opsForValue()
        val newValue = ops.increment(key, amount) ?: amount
        if (redisTemplate.getExpire(key) < 0) {
            redisTemplate.expire(key, TTL)
        }
        return newValue
    }

    @Suppress("unused")
    fun getTodayAmountFallback(
        userId: Long,
        ex: Throwable,
    ): Long {
        fallbackCounter.increment()
        log.warn(
            "[redis-fallback] getTodayAmount userId={} 실패. fail-open 으로 0 반환. cause={}",
            userId,
            ex.message,
        )
        return 0L
    }

    @Suppress("unused")
    fun addTodayAmountFallback(
        userId: Long,
        amount: Long,
        ex: Throwable,
    ): Long {
        fallbackCounter.increment()
        log.warn(
            "[redis-fallback] addTodayAmount userId={} amount={} 실패. 캐시 갱신 누락. cause={}",
            userId,
            amount,
            ex.message,
        )
        return amount
    }

    private fun keyFor(userId: Long): String {
        val today = LocalDate.now(KST).format(YYYYMMDD)
        return "daily:transfer:$userId:$today"
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TTL: Duration = Duration.ofHours(26)
    }
}
