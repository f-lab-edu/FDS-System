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
              event_id, event_version, aggregate_type, aggregate_id, event_type,
              payload, headers, status, attempt_count, created_at, updated_at, next_retry_at
            ) VALUES (:eventId, 1, :aggType, :aggId, :eventType,
                     CAST(:payload AS JSONB), CAST(:headers AS JSONB),
                     'PENDING', 0, :now, :now, :now)
            ON CONFLICT (event_id) DO NOTHING
        """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "eventId" to row.eventId,
                "aggType" to row.aggregateType,
                "aggId" to row.aggregateId,
                "eventType" to row.eventType,
                "payload" to row.payload,
                "headers" to row.headers,
                "now" to timestamp
            )
        )
    }

    /**
     * 처리 대기 중인 이벤트를 조회하고 SENDING 상태로 변경
     *
     * SKIP LOCKED로 동시성 제어
     * 우선순위: PENDING > SENDING(Stuck) > FAILED
     */
    override fun claimBatch(
        limit: Int,
        now: Instant,
        sendingTimeoutSeconds: Long
    ): List<ClaimedRow> {
        val stuckThreshold = Timestamp.from(now.minusSeconds(sendingTimeoutSeconds))
        val currentTime = Timestamp.from(now)

        val sql = """
          WITH grabbed AS (
            SELECT event_id
            FROM transfer_events
            WHERE (
              status IN ('PENDING', 'FAILED')
              OR (status = 'SENDING' AND updated_at < :stuckThreshold)
            )
              AND next_retry_at <= :now
              AND attempt_count < 5
            ORDER BY 
              CASE 
                WHEN status = 'PENDING' THEN 0 
                WHEN status = 'SENDING' THEN 1
                ELSE 2 
              END,
              created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
          )
          UPDATE transfer_events t
             SET status = 'SENDING',
                 attempt_count = CASE 
                   WHEN t.status = 'SENDING' THEN t.attempt_count
                   ELSE t.attempt_count + 1 
                 END,
                 updated_at = :now
            FROM grabbed g
           WHERE t.event_id = g.event_id
          RETURNING t.event_id, t.aggregate_id, t.payload::text AS payload, 
                   t.headers::text AS headers, t.attempt_count
        """.trimIndent()

        return jdbc.query(
            sql,
            mapOf(
                "limit" to limit,
                "now" to currentTime,
                "stuckThreshold" to stuckThreshold
            ),
            claimedRowMapper
        )
    }

    /**
     * Kafka 발행 성공한 이벤트를 PUBLISHED로 변경
     */
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
        """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "ids" to ids,
                "now" to timestamp
            )
        )
    }

    override fun markFailedWithBackoff(
        id: Long,
        cause: String?,
        backoffMillis: Long,
        now: Instant
    ) {
        val currentTime = Timestamp.from(now)
        val nextRetry = Timestamp.from(now.plusMillis(backoffMillis))

        val sql = """
        UPDATE transfer_events
        SET status = CASE 
              WHEN attempt_count >= 5 THEN 'DEAD_LETTER'::transfer_outbox_status
              ELSE 'FAILED'::transfer_outbox_status
            END,
            last_error = :errorMessage,
            updated_at = :now,
            next_retry_at = :nextRetry
        WHERE event_id = :eventId
    """.trimIndent()

        jdbc.update(
            sql, mapOf(
                "eventId" to id,
                "errorMessage" to (cause ?: "UNKNOWN"),
                "now" to currentTime,
                "nextRetry" to nextRetry
            )
        )
    }

    private val claimedRowMapper = RowMapper { rs, _ ->
        ClaimedRow(
            eventId = rs.getLong("event_id"),
            aggregateId = rs.getString("aggregate_id"),
            payload = rs.getString("payload"),
            headers = rs.getString("headers"),
            attemptCount = rs.getInt("attempt_count")
        )
    }
}
