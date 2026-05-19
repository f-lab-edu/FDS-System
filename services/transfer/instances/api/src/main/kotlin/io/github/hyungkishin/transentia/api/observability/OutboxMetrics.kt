package io.github.hyungkishin.transentia.api.observability

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Outbox 의 운영 상태를 Prometheus 로 노출하는 게이지.
 *
 * - outbox_pending_count: PENDING 상태의 row 개수.
 * - outbox_oldest_pending_seconds: 가장 오래된 PENDING row 의 age(초).
 *
 * Prometheus scrape 시점에 게이지가 평가되므로 별도 스케줄러 불필요.
 * Micrometer 가 게이지의 supplier 를 lazy 호출.
 *
 * 알림 연동: monitoring/prometheus-alerts.yml 의
 * OutboxLagGrowing, OutboxOldestPendingTooOld.
 */
@Component
class OutboxMetrics(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {

    private val pendingCount = AtomicLong(0)
    private val oldestPendingSeconds = AtomicLong(0)

    @PostConstruct
    fun register() {
        meterRegistry.gauge("outbox.pending_count", pendingCount) {
            it.set(safeCountPending())
            it.get().toDouble()
        }
        meterRegistry.gauge("outbox.oldest_pending_seconds", oldestPendingSeconds) {
            it.set(safeOldestPendingSeconds())
            it.get().toDouble()
        }
    }

    private fun safeCountPending(): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transfer_events WHERE status = 'PENDING'",
            Long::class.java
        ) ?: 0L
    } catch (e: Exception) {
        // 게이지 실패가 알림 폭증으로 이어지지 않도록 0 반환.
        0L
    }

    private fun safeOldestPendingSeconds(): Long = try {
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(
                EXTRACT(EPOCH FROM (NOW() - MIN(created_at)))::BIGINT,
                0
            )
            FROM transfer_events
            WHERE status = 'PENDING'
            """,
            Long::class.java
        ) ?: 0L
    } catch (e: Exception) {
        0L
    }
}
