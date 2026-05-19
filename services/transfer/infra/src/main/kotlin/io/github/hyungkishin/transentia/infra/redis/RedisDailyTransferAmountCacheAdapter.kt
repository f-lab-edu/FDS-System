package io.github.hyungkishin.transentia.infra.redis

import io.github.hyungkishin.transentia.application.required.DailyTransferAmountCachePort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class RedisDailyTransferAmountCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
) : DailyTransferAmountCachePort {

    override fun getTodayAmount(userId: Long): Long {
        val value = redisTemplate.opsForValue().get(keyFor(userId)) ?: return 0L
        return value.toLongOrNull() ?: 0L
    }

    override fun addTodayAmount(userId: Long, amount: Long): Long {
        val key = keyFor(userId)
        val ops = redisTemplate.opsForValue()
        val newValue = ops.increment(key, amount) ?: amount
        if (redisTemplate.getExpire(key) < 0) {
            redisTemplate.expire(key, TTL)
        }
        return newValue
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
