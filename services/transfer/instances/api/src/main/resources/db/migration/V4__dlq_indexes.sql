-- ============================================
-- V4: DLQ Retry Worker 지원 인덱스
-- ============================================
-- DEAD_LETTER 상태 row 의 부활 후보(updated_at 오래된 순) 인덱스.
-- DlqRetryWorker 가 한 시간에 한 번씩 이 인덱스로 batch 조회한다.

CREATE INDEX IF NOT EXISTS idx_transfer_events_dead_letter
    ON transfer_events (updated_at)
    WHERE status = 'DEAD_LETTER';

COMMENT ON INDEX idx_transfer_events_dead_letter IS
    'DLQ Retry Worker 용. DEAD_LETTER 중 updated_at 오래된 순 조회.';
