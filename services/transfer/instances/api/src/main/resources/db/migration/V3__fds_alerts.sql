-- ============================================
-- V3: FDS 의심 패턴 알림 적재 테이블
-- ============================================
CREATE TABLE IF NOT EXISTS suspicious_pattern_alerts
(
    id                BIGSERIAL PRIMARY KEY,
    account_id        BIGINT       NOT NULL,
    transfer_count    INTEGER      NOT NULL,
    total_amount      BIGINT       NOT NULL,
    window_minutes    BIGINT       NOT NULL,
    last_event_id     VARCHAR(64),
    reason            VARCHAR(500) NOT NULL,
    detected_at       TIMESTAMPTZ  NOT NULL,
    trace_id          VARCHAR(255),
    raw_payload       JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_alerts_account_detected
    ON suspicious_pattern_alerts (account_id, detected_at DESC);

CREATE INDEX IF NOT EXISTS idx_alerts_detected
    ON suspicious_pattern_alerts (detected_at DESC);

COMMENT ON TABLE suspicious_pattern_alerts IS 'FDS 의심 패턴(10분 윈도우 집계) 알림 적재';
COMMENT ON COLUMN suspicious_pattern_alerts.account_id IS '의심 계좌(receiver) ID';
COMMENT ON COLUMN suspicious_pattern_alerts.transfer_count IS '윈도우 내 송금 건수';
COMMENT ON COLUMN suspicious_pattern_alerts.total_amount IS '윈도우 내 총 송금액';
COMMENT ON COLUMN suspicious_pattern_alerts.reason IS '탐지 사유 (분산송금 / 자금세탁 등)';
COMMENT ON COLUMN suspicious_pattern_alerts.raw_payload IS '원본 알림 JSON 보관';
