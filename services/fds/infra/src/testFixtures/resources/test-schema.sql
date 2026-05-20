-- ================================================================
-- FDS infra 통합 테스트용 스키마 (Testcontainers PostgreSQL)
-- 운영 스키마는 transfer-api 의 Flyway 가 관리한다.
-- ================================================================

CREATE TABLE IF NOT EXISTS transactions
(
    id                BIGINT PRIMARY KEY,
    sender_user_id    BIGINT      NOT NULL,
    receiver_user_id  BIGINT      NOT NULL,
    amount            BIGINT      NOT NULL,
    currency          VARCHAR(3)  NOT NULL DEFAULT 'KRW',
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    status_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tx_sender_created
    ON transactions (sender_user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS fraud_detections
(
    id                BIGINT PRIMARY KEY,
    event_id          BIGINT       NOT NULL,
    from_account_id   BIGINT       NOT NULL,
    to_account_id     BIGINT       NOT NULL,
    amount            BIGINT       NOT NULL,
    currency          VARCHAR(10)  NOT NULL,
    total_risk_score  INTEGER      NOT NULL,
    action_type       VARCHAR(20)  NOT NULL,
    rule_results      JSONB        NOT NULL,
    detected_at       TIMESTAMPTZ  NOT NULL,
    trace_id          VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

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
