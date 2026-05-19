-- ============================================
-- V5: Outbox archive 테이블 + 인덱스
-- ============================================
-- LIMITATIONS '2.1 Outbox 누적' 자리 해소.
-- PUBLISHED 상태 일정 기간 지난 row 를 별도 테이블로 이동, 원본 삭제.
-- 운영 쿼리 (transfer_events) 와 회계 감사 쿼리 (archive) 분리.

CREATE TABLE IF NOT EXISTS transfer_events_archive
(
    event_id       BIGINT PRIMARY KEY,
    event_version  INT          NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    attempt_count  INT          NOT NULL,
    error_message  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    archived_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_transfer_events_archive_published
    ON transfer_events_archive (published_at);

COMMENT ON TABLE transfer_events_archive IS
    'Outbox archive — PUBLISHED 14d+ row 이관 보관. 회계/감사 용도.';

-- archive 후보 빠르게 찾기 위한 partial index.
CREATE INDEX IF NOT EXISTS idx_transfer_events_published_old
    ON transfer_events (published_at)
    WHERE status = 'PUBLISHED';
