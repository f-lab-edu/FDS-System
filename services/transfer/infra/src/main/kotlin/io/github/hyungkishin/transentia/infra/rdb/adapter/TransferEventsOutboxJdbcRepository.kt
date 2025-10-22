package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.container.event.TransferEvent
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class TransferEventsOutboxJdbcRepository(
    private val jdbc: NamedParameterJdbcTemplate
) : TransferEventsOutboxRepository {

    override fun save(row: TransferEvent, now: Instant) {
        val timestamp = Timestamp.from(now)

        val sql = """
            INSERT INTO transfer_events(
              event_id, event_version, aggregate_type, event_type,
              payload, headers, status, attempt_count,
              created_at, updated_at, next_retry_at
            ) VALUES (
              :eventId, 1, :aggType, :eventType,
              CAST(:payload AS JSONB), CAST(:headers AS JSONB),
              'PENDING', 0, 
              :now, :now, :now
            )
            ON CONFLICT (event_id) DO UPDATE
            SET status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "eventId" to row.eventId,
                "aggType" to row.aggregateType,
                "eventType" to row.eventType,
                "payload" to row.payload,
                "headers" to row.headers,
                "now" to timestamp
            )
        )
    }

    /**
     * Phase 1: 간단한 Claim (PENDING만 조회)
     * 
     * - FOR UPDATE SKIP LOCKED로 동시성 제어
     * - SENDING 상태 없이 PENDING만 사용
     * - 조회 후 바로 처리
     */
    override fun claimBatch(
        limit: Int,
        now: Instant,
        sendingTimeoutSeconds: Long
    ): List<ClaimedRow> {
        val currentTime = Timestamp.from(now)

        val sql = """
            SELECT 
                event_id,
                payload::text AS payload,
                headers::text AS headers,
                attempt_count
            FROM transfer_events
            WHERE status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
              AND attempt_count < 5
            ORDER BY next_retry_at NULLS FIRST, created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
        """.trimIndent()

        return jdbc.query(
            sql, mapOf(
                "limit" to limit,
                "now" to currentTime
            ), claimedRowMapper
        )
    }

    override fun markAsPublished(
        ids: List<Long>,
        now: Instant
    ) {
        if (ids.isEmpty()) return

        val timestamp = Timestamp.from(now)

        val sql = """
            UPDATE transfer_events 
            SET status = 'PUBLISHED',
                published_at = :now,
                updated_at = :now
            WHERE event_id IN (:ids)
              AND status = 'PENDING'
        """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "ids" to ids,
                "now" to timestamp
            )
        )
    }

    override fun markForRetry(
        eventId: Long,
        attemptCount: Int,
        nextRetryAt: Instant,
        error: String?,
        now: Instant
    ) {
        val sql = """
            UPDATE transfer_events
            SET status = 'PENDING',
                attempt_count = :attemptCount,
                next_retry_at = :nextRetryAt,
                error_message = :error,
                updated_at = :now
            WHERE event_id = :eventId
              AND status = 'PENDING'
        """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "eventId" to eventId,
                "attemptCount" to attemptCount,
                "nextRetryAt" to Timestamp.from(nextRetryAt),
                "error" to error,
                "now" to Timestamp.from(now)
            )
        )
    }

    override fun markAsDeadLetter(eventId: Long, error: String?, now: Instant) {
        val sql = """
            UPDATE transfer_events
            SET status = 'DEAD_LETTER',
                error_message = :error,
                updated_at = :now
            WHERE event_id = :eventId
        """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "eventId" to eventId,
                "error" to error,
                "now" to Timestamp.from(now)
            )
        )
    }

    private val claimedRowMapper = RowMapper { rs, _ ->
        ClaimedRow(
            eventId = rs.getLong("event_id"),
            payload = rs.getString("payload"),
            headers = rs.getString("headers"),
            attemptCount = rs.getInt("attempt_count")
        )
    }
}
