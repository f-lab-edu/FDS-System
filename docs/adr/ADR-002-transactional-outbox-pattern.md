# ADR-002: Transactional Outbox 패턴 채택

- **Status**: Accepted
- **Date**: 2025-12-10
- **Deciders**: Backend Lead
- **Tags**: messaging, kafka, consistency, outbox

---

## Context

송금(transfer) 도메인에서 다음 두 가지 일이 **원자적으로** 일어나야 한다.

1. PostgreSQL 의 `transactions` 테이블에 송금 기록 저장 + `account_balance` 잔액 차감/입금
2. Kafka 의 `transfer-transaction-events` 토픽으로 송금 완료 이벤트 발행 (FDS 가 소비)

이 둘은 서로 다른 시스템(RDB / Kafka)에 속하므로 전통적인 ACID 트랜잭션으로 묶을 수 없다. 발생 가능한 실패 시나리오는 다음과 같다.

### 실패 시나리오 매트릭스

| # | DB 저장 | Kafka 발행 | 결과 | 비즈니스 영향 |
|---|---|---|---|---|
| 1 | 성공 | 성공 | 정상 | 없음 |
| 2 | 실패 | 시도 안 함 | DB 롤백 | 없음 |
| 3 | 성공 | 실패 | **데이터 정합성 깨짐** | FDS 가 송금 이벤트를 받지 못함 → 이상거래 탐지 누락 |
| 4 | 성공 | 성공 후 컨슈머 실패 | (별도 문제) | DLQ 로 해결 |

#3 이 가장 치명적이다. 송금은 일어났는데 FDS 는 모른다.

### 순진한 접근의 함정

```kotlin
// 안티패턴 1: DB 커밋 후 Kafka 발행
@Transactional
fun transfer(...) {
    transactionRepository.save(transaction)  // 커밋됨
}
kafkaTemplate.send(event)  // 여기서 실패하면? -> 유실
```

```kotlin
// 안티패턴 2: 같은 트랜잭션 안에서 Kafka 발행
@Transactional
fun transfer(...) {
    transactionRepository.save(transaction)
    kafkaTemplate.send(event)  // Kafka 발행 성공 후 DB 커밋 실패하면? -> 유령 이벤트
}
```

두 경우 모두 “Dual Write Problem” 의 전형이다.

---

## Decision

**Transactional Outbox 패턴을 채택한다. 송금 트랜잭션 안에서 동일 DB 의 `transfer_events` 테이블에 이벤트를 함께 INSERT 하고, 별도 Relay 컴포넌트가 이를 폴링하여 Kafka 로 발행한다.**

### 1. Outbox 테이블 스키마

```sql
CREATE TABLE transfer_events (
    event_id        BIGINT PRIMARY KEY,            -- Snowflake ID
    aggregate_id    VARCHAR(64) NOT NULL,
    payload         TEXT NOT NULL,                 -- Avro JSON
    headers         TEXT,                          -- traceparent 등
    status          VARCHAR(20) NOT NULL,          -- PENDING / SENDING / PUBLISHED / FAILED
    retry_count     INT DEFAULT 0,
    next_retry_at   TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_outbox_pending ON transfer_events (status, next_retry_at);
```

### 2. 송금 트랜잭션 (동일 DB 커밋)

송금 처리는 `services/transfer/application/.../TransactionService.kt:31` 의 `createTransfer()` 에서 일어난다. `@Transactional` 안에서 트랜잭션·잔액·이벤트가 같은 트랜잭션으로 묶인다.

```kotlin
@Transactional
override fun createTransfer(command: TransferRequestCommand): TransferResponseCommand {
    // ... 검증 + 잔액 차감 + 트랜잭션 저장
    val savedTransaction = transactionRepository.save(transaction)
    val completeEvent = transaction.complete()
    dailyTransferAmountCache.addTodayAmount(sender.id.value, amount.money.rawValue)
    eventPublisher.publishEvent(completeEvent)  // ApplicationEventPublisher (in-process)
    return TransferResponseCommand.from(savedTransaction)
}
```

후속 동작은 `TransferOutboxEventHandler` → `KafkaTransferEventPublisher` 순으로 흐른다.

1. **트랜잭션 커밋 후** Spring `ApplicationEvent` 가 발행된다.
2. `@TransactionalEventListener(phase = AFTER_COMMIT)` 으로 비동기 Kafka 전송을 시도한다.
3. Kafka 전송이 즉시 성공하면 `transfer_events` 에는 행이 들어가지 않는다 (Phase 2 최적화, [ADR-002 부록](#decision-evolution) 참고).
4. Kafka 전송이 실패하면 `transfer_events` 테이블에 `status='PENDING'` 으로 INSERT 한다.
5. Relay 인스턴스가 polling 으로 이를 발행한다.

### 3. Relay 폴링 SQL

```sql
UPDATE transfer_events
SET status = 'SENDING', updated_at = :now
WHERE event_id IN (
    SELECT event_id
    FROM transfer_events
    WHERE status = 'PENDING'
      AND next_retry_at <= :now
      AND MOD(event_id, :totalPartitions) = :partition  -- ADR-003
    ORDER BY event_id
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
RETURNING event_id, aggregate_id, payload, headers
```

`FOR UPDATE SKIP LOCKED` 로 멀티 인스턴스 환경에서 같은 행을 두 번 처리하지 않는다.

---

## Decision Evolution

Outbox 의 진화 과정은 다음 3 단계를 거쳤다.

### Phase 1: 단일 Relay 인스턴스 (2025-10)

- 모든 송금 이벤트를 `transfer_events` 에 무조건 저장.
- `transfer-relay` 라는 별도 Spring Boot 인스턴스가 5초마다 폴링하여 Kafka 전송.
- **문제**: 송금 TPS 증가 시 Outbox 테이블이 빠르게 커지고 Relay 가 병목. 평균 발행 지연 800ms~2s.

### Phase 2: Sync-first + Outbox-fallback (2025-11)

> 커밋: `90b9682 refactoring: 송금 성공시, kafka send 처리 ( 동기 ) 이후, 실패되는 부분만 relay-server 에서 처리하는 방식으로 변경`

- 송금 커밋 후 **즉시 Kafka 전송 시도**.
- 성공하면 `transfer_events` 에 INSERT 하지 않는다 (Outbox 가 “실패 보정용” 으로 격하).
- 실패 시에만 INSERT → Relay 가 백오프로 재시도.
- **효과**: 평균 발행 지연 800ms → 50ms. Outbox 테이블 행 수 99% 감소.

### Phase 3: Relay 멀티 인스턴스 + 파티셔닝 (2025-12)

> 커밋: `9bf9af6`, `82eedde`, `1ba6a6c`

- Relay 인스턴스 3대로 스케일아웃 → 락 경합·빈 배치 문제 발생 → ADR-003 (MOD 파티셔닝)
- 단일 인스턴스 멀티스레드 전략은 Spring Batch 로 이관 (커밋 `1ba6a6c`).

---

## Consequences

### Positive

- **정합성 보장**: 송금 DB 커밋과 이벤트 발행이 항상 At-Least-Once 로 묶인다. #3 시나리오 차단.
- **Kafka 장애 격리**: Kafka 가 다운되어도 송금은 계속 처리된다. 복구 후 Outbox 가 자동 따라잡는다.
- **백오프 + 재시도 내장**: `retry_count`, `next_retry_at` 컬럼으로 Exponential Backoff (2s → 4s → 8s → ... → 10분 cap).
- **순서 보장**: `ORDER BY event_id` (Snowflake = 시간 순) 로 동일 파티션 내 시간 순서 유지.

### Negative

- **이중 쓰기 비용**: 송금 실패 케이스에서 한 트랜잭션 안에 `transactions` + `account_balance` (2건) + `transfer_events` (1건) = 4개 row 가 한꺼번에 들어간다. 단일 송금 트랜잭션이 평균 12ms → 15ms 로 늘었다.
- **Outbox 테이블 정리**: `PUBLISHED` 상태 행을 주기적으로 archive/delete 해야 한다. 현재는 cron 으로 30일 보존.
- **DB 부하**: Relay 가 폴링하므로 빈 배치 조회가 발생한다. ADR-003 의 파티셔닝으로 99% 감소시켰지만 여전히 0 은 아니다.

### Neutral

- **At-Least-Once 의미론**: 컨슈머(FDS)는 멱등 처리를 책임진다. `event_id` 가 Snowflake 라 중복 검출이 쉽다.
- **모니터링 의존**: `transfer_events.status = 'FAILED' AND retry_count >= 10` 행 수를 모니터링해야 한다. Slack 알림 연동은 TODO.

---

## Alternatives Considered

### A. 2-Phase Commit (XA Transaction)

- **장점**: 강한 일관성. 이론적으로 가장 안전.
- **단점**:
  - Kafka 는 XA 트랜잭션을 지원하지 않는다 (Kafka transactions != XA).
  - Atomikos / Bitronix 도입 시 복잡도 폭증.
  - 성능 50% 이상 저하 (블로킹 트랜잭션).
- **기각 사유**: Kafka 가 XA 를 지원하지 않으므로 원천적으로 불가.

### B. Debezium CDC (Change Data Capture)

- **장점**:
  - 애플리케이션 코드에서 Outbox 테이블만 INSERT 하면 끝.
  - Debezium 이 `transactions` 테이블의 binlog/WAL 을 읽어 자동 발행.
- **단점**:
  - 운영 컴포넌트 추가 (Debezium Connect + Kafka Connect 클러스터).
  - 스키마 변경 시 Debezium 설정도 같이 관리.
  - DB binlog/WAL 권한 부여 필요 (PostgreSQL logical replication).
- **기각 사유**: 송금 TPS 가 1,000 미만일 때 Debezium 운영 비용 > 자체 Relay 비용. **Phase 4 후보로 보류.** (README 의 “장기 계획: CDC 전환 (Debezium)” 참고)

### C. Local Topic / In-Memory Buffer

- **장점**: 가장 빠름.
- **단점**: 인스턴스 죽으면 유실. **정합성 보장 0**.
- **기각 사유**: 송금 정합성 요구사항과 충돌.

### D. Kafka Transactions (Exactly-Once Semantics)

- **장점**: Kafka 안에서는 Exactly-Once.
- **단점**: **DB와의 정합성은 여전히 해결되지 않음**. EoS 는 “Kafka → Kafka” 에서만 유효.
- **기각 사유**: Dual Write Problem 의 핵심을 풀지 못한다.

---

## Trade-offs

| 축 | Outbox (채택) | 2PC | Debezium CDC | In-Memory |
|---|---|---|---|---|
| **정합성** | At-Least-Once | Exactly-Once | At-Least-Once | 유실 가능 |
| **Kafka 장애 격리** | 강함 | 약함 (XA 블록) | 강함 | 없음 |
| **운영 복잡도** | 중 (자체 Relay) | 고 (XA 코디네이터) | 고 (Debezium 클러스터) | 저 |
| **성능 (지연)** | 50ms (Phase 2) | +50% | 100~500ms | <10ms |
| **외부 의존** | 없음 | XA Coordinator | Debezium Connect | 없음 |
| **DB 부하 추가** | +20% (Outbox INSERT) | 무시 | binlog 읽기 부하 | 없음 |

---

## References

### 코드 경로

- 송금 트랜잭션: `services/transfer/application/src/main/kotlin/io/github/hyungkishin/transentia/application/TransactionService.kt:31`
- Outbox 어댑터: `services/transfer/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/adapter/.../KafkaTransferEventPublisher.kt`
- Outbox Repository: `services/transfer/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/.../TransferEventsOutboxJdbcRepository.kt`
- Relay 메인 로직: `services/transfer/instances/transfer-relay/`
- Flyway 마이그레이션: `services/transfer/infra/src/main/resources/db/migration/`
- 상세 설계 문서: `docs/etc/outbox-pattern.md`, `docs/etc/outbox 테이블에 대하여.md`

### 외부 자료

- Chris Richardson, *Microservices Patterns* (2018), Ch. 3 - Transactional Outbox
- Gunnar Morling, "Reliable Microservices Data Exchange With the Outbox Pattern" (Debezium Blog, 2019)
- Confluent, "Reliable Data Streaming with Kafka and Outbox Pattern"

---

## Related ADRs

- ADR-001: Hexagonal Architecture (Outbox 어댑터는 infra 모듈에 격리)
- ADR-003: Relay Partitioning by Modulo (Outbox 멀티 인스턴스 처리)
- ADR-007: Distributed Tracing Context Propagation (Outbox payload 의 `headers` 컬럼이 traceparent 전파에 사용됨)
