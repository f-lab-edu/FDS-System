-- ENUM 타입 정의
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_status') THEN
        CREATE TYPE user_status AS ENUM ('ACTIVE','SUSPENDED','DEACTIVATED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
        CREATE TYPE user_role AS ENUM ('USER','ADMIN','AUDITOR');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'account_type') THEN
        CREATE TYPE account_type AS ENUM ('CHECKING', 'SAVINGS', 'DEPOSIT');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'account_status') THEN
        CREATE TYPE account_status AS ENUM ('ACTIVE', 'SUSPENDED', 'CLOSED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'transaction_status') THEN
        CREATE TYPE transaction_status AS ENUM ('PENDING','COMPLETED','FAILED','CORRECTED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'transaction_history_status') THEN
        CREATE TYPE transaction_history_status AS ENUM ('SUCCESS','FAIL');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'transfer_outbox_status') THEN
        CREATE TYPE transfer_outbox_status AS ENUM ('PENDING','PUBLISHED','DEAD_LETTER');
    END IF;
END $$;

-- users 테이블
CREATE TABLE IF NOT EXISTS users (
    id                   BIGINT PRIMARY KEY, -- SnowFlakeId
    name                 VARCHAR(100) NOT NULL,
    email                VARCHAR(255) UNIQUE NOT NULL,
    status               user_status NOT NULL DEFAULT 'ACTIVE',
    is_transfer_locked   BOOLEAN NOT NULL DEFAULT false,
    transfer_lock_reason VARCHAR(500),
    daily_transfer_limit BIGINT NOT NULL DEFAULT 5000000,
    role                 user_role NOT NULL DEFAULT 'USER',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_users_status_locked ON users (status, is_transfer_locked);

COMMENT ON TABLE users IS '송금 시스템 사용자 정보';
COMMENT ON COLUMN users.id IS '사용자 고유 ID (Snowflake)';
COMMENT ON COLUMN users.name IS '사용자 이름';
COMMENT ON COLUMN users.email IS '사용자 이메일 (UNIQUE)';
COMMENT ON COLUMN users.status IS '계정 상태: ACTIVE, SUSPENDED, DEACTIVATED';
COMMENT ON COLUMN users.is_transfer_locked IS '송금 잠금 여부 (이상탐지, 관리자 제재 등)';
COMMENT ON COLUMN users.daily_transfer_limit IS '1일 최대 송금 가능 금액';
COMMENT ON COLUMN users.role IS '사용자 역할: USER, ADMIN, AUDITOR';
COMMENT ON COLUMN users.created_at IS '계정 생성 일시';
COMMENT ON COLUMN users.updated_at IS '마지막 정보 갱신 일시';

-- account_balances 테이블
CREATE TABLE IF NOT EXISTS account_balances (
    id             BIGINT PRIMARY KEY, -- SnowFlakeId
    user_id        BIGINT NOT NULL REFERENCES users(id),
    account_number VARCHAR(20) NOT NULL,
    balance        BIGINT NOT NULL DEFAULT 0,
    account_type   account_type NOT NULL DEFAULT 'CHECKING',
    status         account_status NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_account_balances_account_number 
ON account_balances (account_number);

CREATE INDEX IF NOT EXISTS idx_account_balances_user_id 
ON account_balances (user_id);

COMMENT ON TABLE account_balances IS '사용자 계좌 잔액';
COMMENT ON COLUMN account_balances.id IS '계좌 ID (Snowflake)';
COMMENT ON COLUMN account_balances.user_id IS '사용자 ID';
COMMENT ON COLUMN account_balances.account_number IS '계좌번호';
COMMENT ON COLUMN account_balances.balance IS '잔액';
COMMENT ON COLUMN account_balances.account_type IS '계좌 유형';
COMMENT ON COLUMN account_balances.status IS '계좌 상태';
COMMENT ON COLUMN account_balances.version IS '낙관적 락 버전';
COMMENT ON COLUMN account_balances.created_at IS '계좌 생성 일시';
COMMENT ON COLUMN account_balances.updated_at IS '마지막 갱신 일시';

-- transactions 테이블
CREATE TABLE IF NOT EXISTS transactions (
    id                BIGINT PRIMARY KEY, -- SnowFlakeId
    sender_user_id    BIGINT NOT NULL REFERENCES users(id),
    receiver_user_id  BIGINT NOT NULL REFERENCES users(id),
    amount            BIGINT NOT NULL CHECK (amount > 0),
    status            transaction_status NOT NULL DEFAULT 'PENDING',
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    status_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT ck_tx_sender_ne_receiver CHECK (sender_user_id <> receiver_user_id)
);

CREATE INDEX IF NOT EXISTS idx_tx_sender_created 
ON transactions (sender_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tx_receiver_created 
ON transactions (receiver_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tx_status_updated 
ON transactions (status, status_updated_at DESC);

COMMENT ON TABLE transactions IS '송금 트랜잭션';
COMMENT ON COLUMN transactions.id IS '트랜잭션 ID';
COMMENT ON COLUMN transactions.sender_user_id IS '보낸 사용자 ID';
COMMENT ON COLUMN transactions.receiver_user_id IS '받는 사용자 ID';
COMMENT ON COLUMN transactions.amount IS '송금 금액';
COMMENT ON COLUMN transactions.status IS '상태: PENDING, COMPLETED, FAILED, CORRECTED';
COMMENT ON COLUMN transactions.received_at IS '수신/요청 시각';
COMMENT ON COLUMN transactions.created_at IS '생성 시각';
COMMENT ON COLUMN transactions.status_updated_at IS '상태 최종 갱신 시각';

-- transaction_histories 테이블
CREATE TABLE IF NOT EXISTS transaction_histories (
    id             BIGINT PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    status         transaction_history_status NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tx_histories_txid_created
ON transaction_histories (transaction_id, created_at);

COMMENT ON TABLE transaction_histories IS '트랜잭션 상태 변경 이력';
COMMENT ON COLUMN transaction_histories.status IS '최종 송금 상태';
COMMENT ON COLUMN transaction_histories.transaction_id IS '참조 트랜잭션 ID';
COMMENT ON COLUMN transaction_histories.created_at IS '생성 시각';

-- transfer_events (Outbox)
CREATE TABLE IF NOT EXISTS transfer_events (
    event_id       BIGINT PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    payload        JSONB NOT NULL,
    status         transfer_outbox_status NOT NULL DEFAULT 'PENDING',
    attempt_count  INT NOT NULL DEFAULT 0,
    error_message  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    
    CONSTRAINT ck_transfer_events_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_published_requires_timestamp CHECK (
        status <> 'PUBLISHED' OR published_at IS NOT NULL
    ),
    CONSTRAINT ck_attempt_count_positive CHECK (attempt_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_transfer_events_pending
ON transfer_events (created_at)
WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_transfer_events_dead_letter
ON transfer_events (created_at)
WHERE status = 'DEAD_LETTER';

COMMENT ON TABLE transfer_events IS 'Outbox: Kafka 발행 실패 시 재시도용';
COMMENT ON COLUMN transfer_events.event_id IS '이벤트 고유 ID (Snowflake)';
COMMENT ON COLUMN transfer_events.transaction_id IS '참조 트랜잭션 ID';
COMMENT ON COLUMN transfer_events.payload IS 'FDS 전송용 이벤트 데이터 (JSONB)';
COMMENT ON COLUMN transfer_events.status IS '상태: PENDING, PUBLISHED, DEAD_LETTER';
COMMENT ON COLUMN transfer_events.attempt_count IS '발행 재시도 누적 횟수';
COMMENT ON COLUMN transfer_events.error_message IS '최근 실패 에러 메시지';
COMMENT ON COLUMN transfer_events.created_at IS 'Outbox 레코드 생성 시각';
COMMENT ON COLUMN transfer_events.published_at IS 'Kafka 발행 성공 시각';

-- updated_at 자동 갱신 함수
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- updated_at 트리거
CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_account_balances_updated_at
    BEFORE UPDATE ON account_balances
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- status_updated_at 자동 갱신 함수
CREATE OR REPLACE FUNCTION update_status_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        NEW.status_updated_at = now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- status_updated_at 트리거
CREATE TRIGGER trg_transactions_status_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW
    EXECUTE FUNCTION update_status_updated_at_column();
