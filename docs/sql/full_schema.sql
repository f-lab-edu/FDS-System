-- PostgreSQL DDL generated from FDS ERD
SET client_encoding = 'UTF8';

-- ENUM 타입 정의
CREATE TYPE user_status AS ENUM ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'); -- 유저 상태
CREATE TYPE user_role AS ENUM ('USER', 'ADMIN', 'AUDITOR'); -- 유저 권한
CREATE TYPE currency_code AS ENUM ('KRW', 'USD', 'JPY', 'BTC', 'ETH'); -- 통화 코드
CREATE TYPE transaction_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'CORRECTED'); -- 트랜잭션 상태
CREATE TYPE final_decision_type AS ENUM ('PASS', 'REVIEW', 'BLOCK'); -- 이상 탐지 최종 판정 결과: PASS(정상), REVIEW(검토), BLOCK(차단)
CREATE TYPE changer_role_type AS ENUM ('SYSTEM', 'ADMIN', 'USER'); -- 송금 주체 변경 유형: SYSTEM(시스템 자동 처리), ADMIN(관리자 조작), USER(사용자 요청)

-- ============================================
-- USER DOMAIN
-- ============================================

-- 사용자 테이블
CREATE TABLE users (
                       id BIGINT PRIMARY KEY,
                       name TEXT NOT NULL,
                       email TEXT UNIQUE NOT NULL,
                       status user_status NOT NULL DEFAULT 'ACTIVE',
                       is_transfer_locked BOOLEAN NOT NULL DEFAULT false,
                       daily_transfer_limit BIGINT NOT NULL DEFAULT 1000000,
                       role user_role NOT NULL DEFAULT 'USER',
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 인덱스
CREATE INDEX idx_users_email_created_at ON users(email, created_at);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_role ON users(role);

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


CREATE TABLE account_balances (
                                  user_id BIGINT PRIMARY KEY REFERENCES users(id),
                                  balance NUMERIC(20, 8) NOT NULL DEFAULT 0,
                                  currency currency_code NOT NULL DEFAULT 'KRW',
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  updated_at TIMESTAMPTZ NULL DEFAULT now()
);

COMMENT ON TABLE account_balances IS '사용자의 계좌 잔액 정보';
COMMENT ON COLUMN account_balances.user_id IS '사용자 ID (users.id FK)';
COMMENT ON COLUMN account_balances.balance IS '현재 보유 금액 (소수점 8자리까지 지원)';
COMMENT ON COLUMN account_balances.currency IS '계좌의 기준 통화 (기본값: KRW)';
COMMENT ON COLUMN account_balances.created_at IS '초기 생성 시각';
COMMENT ON COLUMN account_balances.updated_at IS '마지막 변경 시각';

CREATE INDEX idx_account_balances_updated_at ON account_balances(updated_at);
CREATE INDEX idx_account_balances_currency ON account_balances(currency);

-- Phase-3
CREATE TABLE wallet_addresses (
                                  id BIGINT PRIMARY KEY,
                                  user_id BIGINT NOT NULL,
                                  address TEXT UNIQUE NOT NULL,
                                  chain_code TEXT NOT NULL,
                                  registered_at TIMESTAMPTZ DEFAULT now() NOT NULL
);

COMMENT ON TABLE wallet_addresses IS '사용자 지갑 주소';
COMMENT ON COLUMN wallet_addresses.id IS '지갑 주소 ID (Snowflake)';
COMMENT ON COLUMN wallet_addresses.user_id IS '사용자 ID';
COMMENT ON COLUMN wallet_addresses.address IS '온체인 지갑 주소 (UNIQUE)';
COMMENT ON COLUMN wallet_addresses.chain_code IS '체인 코드 (예: ETH)';
COMMENT ON COLUMN wallet_addresses.registered_at IS '등록 시각';

CREATE INDEX idx_wallet_addresses_user_chain ON wallet_addresses(user_id, chain_code);

-- ============================================
-- ADMIN DOMAIN
-- ============================================

CREATE TABLE admin_users (
                             id BIGINT PRIMARY KEY,
                             username TEXT UNIQUE NOT NULL,
                             email TEXT UNIQUE NOT NULL,
                             role TEXT NOT NULL CHECK (role IN ('ADMIN', 'AUDITOR')),
                             created_at TIMESTAMPTZ DEFAULT now() NOT NULL
);

COMMENT ON TABLE admin_users IS '관리자 계정';
COMMENT ON COLUMN admin_users.id IS '관리자 ID';
COMMENT ON COLUMN admin_users.username IS '관리자 계정명';
COMMENT ON COLUMN admin_users.email IS '이메일';
COMMENT ON COLUMN admin_users.role IS '관리자 권한 (ADMIN / AUDITOR)';
COMMENT ON COLUMN admin_users.created_at IS '생성 시각';

CREATE UNIQUE INDEX unq_admin_users_email ON admin_users(email);
CREATE INDEX idx_admin_user_id_role ON admin_users(id, role);
CREATE INDEX idx_admin_user_created_at ON admin_users(created_at);

-- ============================================
-- TRANSACTION DOMAIN
-- ============================================

CREATE TABLE transactions (
                              id BIGINT PRIMARY KEY,
                              sender_user_id BIGINT NOT NULL REFERENCES users(id),
                              receiver_user_id BIGINT NOT NULL REFERENCES users(id),
                              amount NUMERIC(20, 8) NOT NULL CHECK (amount > 0),
                              currency currency_code NOT NULL DEFAULT 'KRW',
                              status transaction_status NOT NULL,
                              exchange_rate_id BIGINT,
                              received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                              status_updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE transactions IS '송금 트랜잭션';
COMMENT ON COLUMN transactions.id IS '트랜잭션 ID';
COMMENT ON COLUMN transactions.sender_user_id IS '보낸 사용자 ID';
COMMENT ON COLUMN transactions.receiver_user_id IS '받는 사용자 ID';
COMMENT ON COLUMN transactions.amount IS '송금 금액';
COMMENT ON COLUMN transactions.currency IS '송금 트랜잭션 통화 코드 (예: KRW, USD)';
COMMENT ON COLUMN transactions.status IS '트랜잭션 상태: PENDING, COMPLETED, FAILED, CORRECTED';
COMMENT ON COLUMN transactions.exchange_rate_id IS '참조 환율 ID';
COMMENT ON COLUMN transactions.received_at IS '수신 확인 시각';
COMMENT ON COLUMN transactions.created_at IS '트랜잭션 생성 시각';
COMMENT ON COLUMN transactions.status_updated_at IS '트랜잭션 상태가 마지막으로 변경된 시각';

CREATE INDEX idx_transactions_sender_created ON transactions(sender_user_id, created_at);
CREATE INDEX idx_transactions_receiver_created ON transactions(receiver_user_id, created_at);

CREATE TABLE tx_history (
                            id BIGINT PRIMARY KEY,
                            tx_id BIGINT NOT NULL REFERENCES transactions(id),
                            prev_status transaction_status NOT NULL,
                            next_status transaction_status NOT NULL,
                            changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            changed_by BIGINT NOT NULL,
                            changer_role changer_role_type NOT NULL DEFAULT 'SYSTEM'
);

CREATE INDEX idx_tx_history_tx_id ON tx_history(tx_id);
COMMENT ON TABLE tx_history IS '트랜잭션 상태 변경 이력';
COMMENT ON COLUMN tx_history.id IS '이력 ID';
COMMENT ON COLUMN tx_history.tx_id IS '트랜잭션 참조 ID';
COMMENT ON COLUMN tx_history.prev_status IS '이전 상태';
COMMENT ON COLUMN tx_history.next_status IS '변경 후 상태';
COMMENT ON COLUMN tx_history.changed_at IS '변경 시각';
COMMENT ON COLUMN tx_history.changed_by IS '변경한 관리자 ID';
COMMENT ON COLUMN tx_history.changer_role IS '상태 변경 주체 유형: SYSTEM(시스템 자동 처리), ADMIN(관리자 조작), USER(사용자 요청)';

CREATE TABLE correction_log (
                                id BIGINT PRIMARY KEY,
                                tx_id BIGINT NOT NULL REFERENCES transactions(id),
                                new_tx_id BIGINT NOT NULL REFERENCES transactions(id),
                                amount NUMERIC(20, 8) NOT NULL,
                                currency currency_code NOT NULL DEFAULT 'KRW',
                                restored_at TIMESTAMPTZ NOT NULL,
                                restored_by BIGINT NOT NULL,
                                reason TEXT NOT NULL
);

COMMENT ON TABLE correction_log IS '트랜잭션 정정 이력 (정상 처리된 송금을 관리자 또는 시스템이 보정한 경우)';
COMMENT ON COLUMN correction_log.tx_id IS '원본 트랜잭션 ID (정정 대상)';
COMMENT ON COLUMN correction_log.new_tx_id IS '정정된 신규 트랜잭션 ID (대체 송금)';
COMMENT ON COLUMN correction_log.amount IS '정정된 금액 (대체 트랜잭션에 반영된 금액)';
COMMENT ON COLUMN correction_log.currency IS '정정 금액의 통화';
COMMENT ON COLUMN correction_log.restored_at IS '정정 처리 시각';
COMMENT ON COLUMN correction_log.restored_by IS '정정 처리 관리자 ID';
COMMENT ON COLUMN correction_log.reason IS '정정 사유 (예: 오입력, 환불, 보정 등)';

CREATE UNIQUE INDEX uniq_correction_log_tx_id ON correction_log(tx_id);
CREATE INDEX idx_tx_id_restored_by ON correction_log(tx_id, restored_by);
CREATE INDEX idx_new_tx_id_restored_by ON correction_log(new_tx_id, restored_by);

-- ============================================
-- RISK DETECTION DOMAIN (Phase-2)
-- ============================================

CREATE TABLE rules (
                       id BIGINT PRIMARY KEY,
                       rule_name TEXT UNIQUE NOT NULL,
                       condition_json JSON NOT NULL,
                       threshold INT NOT NULL,
                       enabled BOOLEAN NOT NULL DEFAULT TRUE,
                       created_at TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE rules IS '이상거래 탐지 룰 정의 (현재 적용 중인 룰 세트)';
COMMENT ON COLUMN rules.rule_name IS '룰 이름 (고유)';
COMMENT ON COLUMN rules.condition_json IS '룰 조건 정의 (JSON)';
COMMENT ON COLUMN rules.threshold IS '위험 판단 기준값 (ex: 스코어 80 이상)';
COMMENT ON COLUMN rules.enabled IS '활성화 여부 (true/false)';
COMMENT ON COLUMN rules.created_at IS '룰 등록 일시';

CREATE TABLE rule_history (
                              id BIGINT PRIMARY KEY,
                              rule_id BIGINT NOT NULL REFERENCES rules(id),
                              version INT NOT NULL,
                              condition_json JSON NOT NULL,
                              threshold INT NOT NULL,
                              created_by BIGINT NOT NULL REFERENCES admin_users(id),
                              created_at TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE rule_history IS '룰 변경 이력 (버전 기반 정책 추적)';
COMMENT ON COLUMN rule_history.rule_id IS '참조 대상 룰 ID';
COMMENT ON COLUMN rule_history.version IS '룰 버전 (1부터 증가)';
COMMENT ON COLUMN rule_history.condition_json IS '해당 버전의 룰 조건';
COMMENT ON COLUMN rule_history.threshold IS '버전별 임계값';
COMMENT ON COLUMN rule_history.created_by IS '버전 작성 관리자 ID';
COMMENT ON COLUMN rule_history.created_at IS '버전 생성 시각';

CREATE UNIQUE INDEX uniq_rule_version ON rule_history(rule_id, version);

CREATE TABLE risk_logs (
                           id BIGINT PRIMARY KEY,
                           tx_id BIGINT NOT NULL,
                           rule_hit BOOLEAN,
                           ai_score DOUBLE PRECISION,
                           final_decision final_decision_type NOT NULL,
                           evaluated_at TIMESTAMPTZ DEFAULT now()
);

COMMENT ON TABLE risk_logs IS 'FDS 위험 탐지 로그 (트랜잭션 기반 룰/AI 분석 결과)';
COMMENT ON COLUMN risk_logs.tx_id IS '분석 대상 트랜잭션 ID (transactions.id 논리 참조)';
COMMENT ON COLUMN risk_logs.rule_hit IS '사전 정의 룰이 적중했는지 여부';
COMMENT ON COLUMN risk_logs.ai_score IS 'AI 모델 기반 이상거래 위험도 점수';
COMMENT ON COLUMN risk_logs.final_decision IS '최종 판정 결과: PASS(정상), REVIEW(검토), BLOCK(차단)';
COMMENT ON COLUMN risk_logs.evaluated_at IS '분석 수행 시각';

CREATE INDEX idx_risk_logs_txid_decision ON risk_logs(tx_id, final_decision);
CREATE INDEX idx_risk_logs_eval ON risk_logs(evaluated_at);

CREATE TABLE risk_rule_hits (
                                id BIGINT PRIMARY KEY,
                                risk_log_id BIGINT NOT NULL,
                                rule_id BIGINT NOT NULL,
                                hit BOOLEAN NOT NULL,
                                score DOUBLE PRECISION NOT NULL
);

COMMENT ON TABLE risk_rule_hits IS '탐지 로그에 대한 룰별 적중 결과';
COMMENT ON COLUMN risk_rule_hits.id IS '룰 히트 기록 ID';
COMMENT ON COLUMN risk_rule_hits.risk_log_id IS '위험 탐지 로그 ID (risk_logs.id)';
COMMENT ON COLUMN risk_rule_hits.rule_id IS '적용된 탐지 룰 ID (rules.id)';
COMMENT ON COLUMN risk_rule_hits.hit IS '룰 적중 여부 (true/false)';
COMMENT ON COLUMN risk_rule_hits.score IS '해당 룰 기준 점수 (모델 또는 조건 기반)';

CREATE INDEX idx_risk_hit_rule ON risk_rule_hits(rule_id);
CREATE INDEX idx_risk_hit_log ON risk_rule_hits(risk_log_id);

-- ============================================
-- EXCHANGE DOMAIN
-- ============================================

CREATE TABLE exchange_rates (
                                id BIGINT PRIMARY KEY,
                                from_currency currency_code NOT NULL,
                                to_currency currency_code NOT NULL,
                                rate NUMERIC(18,8) NOT NULL,
                                fetched_at TIMESTAMPTZ NOT NULL,
                                source TEXT NOT NULL,
                                is_batch BOOLEAN DEFAULT FALSE
);

COMMENT ON TABLE exchange_rates IS '송금 및 온체인 환산을 위한 환율 정보';
COMMENT ON COLUMN exchange_rates.id IS '환율 레코드 ID';
COMMENT ON COLUMN exchange_rates.from_currency IS '기준 통화 (예: KRW)';
COMMENT ON COLUMN exchange_rates.to_currency IS '대상 통화 (예: USD)';
COMMENT ON COLUMN exchange_rates.rate IS '환율 값 (from → to 변환 비율)';
COMMENT ON COLUMN exchange_rates.fetched_at IS '환율 조회 시각 (실제 적용 기준)';
COMMENT ON COLUMN exchange_rates.source IS '환율 데이터 출처 (API 또는 수동)';
COMMENT ON COLUMN exchange_rates.is_batch IS '배치 수집 여부 (true: 배치 처리, false: 실시간)';

CREATE INDEX idx_exchange_composite ON exchange_rates(from_currency, to_currency, source, fetched_at);

-- ============================================
-- DLQ DOMAIN
-- ============================================

CREATE TABLE dlq_events (
                            id BIGINT PRIMARY KEY,
                            tx_id BIGINT NOT NULL,
                            component TEXT NOT NULL,
                            error_message TEXT NOT NULL,
                            received_at TIMESTAMPTZ NOT NULL,
                            resolved BOOLEAN DEFAULT FALSE,
                            resolved_at TIMESTAMPTZ
);

COMMENT ON TABLE dlq_events IS 'Kafka 또는 비동기 처리 중 실패한 이벤트 기록';
COMMENT ON COLUMN dlq_events.tx_id IS '실패 연관 트랜잭션 ID (transactions.id 논리 참조)';
COMMENT ON COLUMN dlq_events.component IS '실패 발생 컴포넌트 (예: risk-processor, onchain-writer 등)';
COMMENT ON COLUMN dlq_events.error_message IS '오류 메시지 (예: NPE, JSON parse 실패 등)';
COMMENT ON COLUMN dlq_events.received_at IS 'DLQ 수신 시각';
COMMENT ON COLUMN dlq_events.resolved IS '해결 여부 (수동/자동 처리됨)';
COMMENT ON COLUMN dlq_events.resolved_at IS '해결된 시각';

CREATE INDEX idx_dlq_tx_id ON dlq_events(tx_id);

-- 미 해결된 장애 이벤트 조회시 (PostgreSQL에서 지원하는 Partial Index (부분 인덱스))
CREATE INDEX idx_dlq_resolved_false ON dlq_events(resolved) WHERE resolved = false;

-- ============================================
-- ONCHAIN DOMAIN (Phase-3) 예정....
-- ============================================

CREATE TABLE chains (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        rpc_url TEXT,
                        explorer_url TEXT
);
COMMENT ON TABLE chains IS '블록체인 체인 메타정보';
COMMENT ON COLUMN chains.code IS '체인 코드';
COMMENT ON COLUMN chains.name IS '체인 이름';
COMMENT ON COLUMN chains.rpc_url IS 'RPC URL';
COMMENT ON COLUMN chains.explorer_url IS '탐색기 URL';

CREATE TABLE tokens (
                        symbol TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        decimals INT NOT NULL,
                        contract_address TEXT NOT NULL,
                        chain_code TEXT NOT NULL
);
CREATE INDEX idx_tokens_chain_code ON tokens(chain_code);
COMMENT ON TABLE tokens IS '온체인 토큰 정보';
COMMENT ON COLUMN tokens.symbol IS '토큰 심볼';
COMMENT ON COLUMN tokens.name IS '토큰 이름';
COMMENT ON COLUMN tokens.decimals IS '소수점 자리수';
COMMENT ON COLUMN tokens.contract_address IS '컨트랙트 주소';
COMMENT ON COLUMN tokens.chain_code IS '소속 체인 코드';

CREATE TABLE wallet_transfers (
                                  id BIGINT PRIMARY KEY,
                                  tx_id BIGINT NOT NULL,
                                  from_wallet_id BIGINT NOT NULL,
                                  to_wallet_id BIGINT NOT NULL,
                                  to_user_id BIGINT NOT NULL,
                                  exchange_rate_id BIGINT,
                                  token_symbol TEXT NOT NULL,
                                  chain_code TEXT NOT NULL,
                                  amount NUMERIC(20, 8) NOT NULL,
                                  status TEXT NOT NULL CHECK (status IN ('REQUESTED', 'PENDING', 'CONFIRMED', 'FAILED')),
                                  onchain_tx_id TEXT NOT NULL,
                                  created_at TIMESTAMPTZ DEFAULT now() NOT NULL
);

COMMENT ON TABLE wallet_transfers IS '온체인 지갑 송금';
COMMENT ON COLUMN wallet_transfers.id IS '송금 ID';
COMMENT ON COLUMN wallet_transfers.tx_id IS '내부 트랜잭션 참조';
COMMENT ON COLUMN wallet_transfers.from_wallet_id IS '보낸 지갑';
COMMENT ON COLUMN wallet_transfers.to_wallet_id IS '받는 지갑';
COMMENT ON COLUMN wallet_transfers.to_user_id IS '수신 사용자';
COMMENT ON COLUMN wallet_transfers.exchange_rate_id IS '환율 참조';
COMMENT ON COLUMN wallet_transfers.token_symbol IS '토큰 심볼';
COMMENT ON COLUMN wallet_transfers.chain_code IS '체인 코드';
COMMENT ON COLUMN wallet_transfers.amount IS '금액';
COMMENT ON COLUMN wallet_transfers.status IS '송금 상태';
COMMENT ON COLUMN wallet_transfers.onchain_tx_id IS '온체인 트랜잭션 ID';
COMMENT ON COLUMN wallet_transfers.created_at IS '생성 시각';

CREATE INDEX idx_wallet_transfers_from_wallet ON wallet_transfers(from_wallet_id);
CREATE INDEX idx_wallet_transfers_to_wallet ON wallet_transfers(to_wallet_id);
CREATE INDEX idx_wallet_transfers_to_user ON wallet_transfers(to_user_id);
CREATE INDEX idx_wallet_transfers_status ON wallet_transfers(status);
CREATE INDEX idx_wallet_transfers_tx_hash ON wallet_transfers(onchain_tx_id);
CREATE INDEX idx_wallet_transfers_combo ON wallet_transfers(chain_code, token_symbol, status);

CREATE TYPE onchain_tx_status AS ENUM ('SENT', 'CONFIRMED', 'REORGED', 'DROPPED');

CREATE TABLE onchain_tx_logs (
                                 id BIGINT PRIMARY KEY,
                                 wallet_transfer_id BIGINT NOT NULL,
                                 tx_hash TEXT NOT NULL,
                                 status onchain_tx_status NOT NULL,
                                 confirmed_block INT,
                                 confirmed_at TIMESTAMPTZ
);

COMMENT ON TABLE onchain_tx_logs IS '온체인 전송 로그';
COMMENT ON COLUMN onchain_tx_logs.id IS '로그 ID';
COMMENT ON COLUMN onchain_tx_logs.wallet_transfer_id IS '참조 송금 ID';
COMMENT ON COLUMN onchain_tx_logs.tx_hash IS '트랜잭션 해시';
COMMENT ON COLUMN onchain_tx_logs.status IS '상태';
COMMENT ON COLUMN onchain_tx_logs.confirmed_block IS '확정 블록';
COMMENT ON COLUMN onchain_tx_logs.confirmed_at IS '확정 시각';

CREATE INDEX idx_onchain_tx_hash ON onchain_tx_logs(tx_hash);
CREATE INDEX idx_onchain_log_composite ON onchain_tx_logs(wallet_transfer_id, status);