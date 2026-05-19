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
     * Claim + Read (원자적 처리)
     * 
     * 개선사항:
     * 1. FOR UPDATE SKIP LOCKED로 경합 방지
     * 2. 재시도 대상 포함 (next_retry_at 지난 것)
     * 3. 최대 재시도 횟수 체크 (attempt_count < 5)
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
              AND attempt_count < :maxAttempts
            ORDER BY next_retry_at NULLS FIRST, created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
        """.trimIndent()

        return jdbc.query(
            sql, mapOf(
                "limit" to limit,
                "now" to currentTime,
                "maxAttempts" to 5
            ), claimedRowMapper
        )
    }

    /**
     * 발행 완료 처리
     * 
     * 개선사항:
     * - status 체크 제거 (PENDING에서 바로 PUBLISHED로)
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

    /**
     * 재시도 예약
     * 
     * - status는 PENDING 유지
     * - attempt_count 증가
     * - next_retry_at 설정
     */
    override fun markForRetry(
        eventId: Long,
        attemptCount: Int,
        nextRetryAt: Instant,
        error: String?,
        now: Instant
    ) {
        val sql = """
            UPDATE transfer_events
            SET attempt_count = :attemptCount,
                next_retry_at = :nextRetryAt,
                error_message = :error,
                updated_at = :now
            WHERE event_id = :eventId
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

    /**
     * DLQ 이동
     */
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

    /**
     * DEAD_LETTER 중 일정 시간 지난 row 를 PENDING 으로 되돌린다.
     * SKIP LOCKED 로 다른 DLQ Worker 인스턴스와 경합 방지.
     */
    override fun reviveDeadLetters(olderThan: Instant, limit: Int, now: Instant): Int {
        val sql = """
            UPDATE transfer_events
            SET status = 'PENDING',
                attempt_count = 0,
                next_retry_at = :now,
                updated_at = :now
            WHERE event_id IN (
                SELECT event_id FROM transfer_events
                WHERE status = 'DEAD_LETTER'
                  AND updated_at <= :olderThan
                ORDER BY updated_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
        """.trimIndent()

        return jdbc.update(
            sql, mapOf(
                "now" to Timestamp.from(now),
                "olderThan" to Timestamp.from(olderThan),
                "limit" to limit,
            )
        )
    }

    override fun countDeadLetters(): Long {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_events WHERE status = 'DEAD_LETTER'",
            mapOf<String, Any>(),
            Long::class.java
        ) ?: 0L
    }

    /**
     * Archive: PUBLISHED + published_at <= olderThan 인 row 를
     * INSERT … SELECT 후 같은 트랜잭션에서 DELETE.
     * 외부에서 @Transactional 보장 가정.
     */
    override fun archivePublished(olderThan: Instant, limit: Int): Int {
        val insertSql = """
            WITH picked AS (
                SELECT event_id FROM transfer_events
                WHERE status = 'PUBLISHED' AND published_at <= :olderThan
                ORDER BY published_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            INSERT INTO transfer_events_archive
              (event_id, event_version, aggregate_type, event_type, payload, headers,
               status, attempt_count, error_message, created_at, updated_at, published_at)
            SELECT
              e.event_id, e.event_version, e.aggregate_type, e.event_type, e.payload, e.headers,
              e.status::text, e.attempt_count, e.error_message, e.created_at, e.updated_at, e.published_at
            FROM transfer_events e JOIN picked p ON p.event_id = e.event_id
            ON CONFLICT (event_id) DO NOTHING
        """.trimIndent()

        val inserted = jdbc.update(
            insertSql, mapOf(
                "olderThan" to Timestamp.from(olderThan),
                "limit" to limit,
            )
        )
        if (inserted == 0) return 0

        val deleteSql = """
            DELETE FROM transfer_events
            WHERE event_id IN (
              SELECT event_id FROM transfer_events_archive
              WHERE archived_at >= :now AND published_at <= :olderThan
            )
        """.trimIndent()

        jdbc.update(
            deleteSql, mapOf(
                "now" to Timestamp.from(olderThan.minusSeconds(0)), // archive 직후
                "olderThan" to Timestamp.from(olderThan),
            )
        )
        return inserted
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
