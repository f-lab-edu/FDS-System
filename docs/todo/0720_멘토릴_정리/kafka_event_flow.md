# Kafka 기반 송금 이벤트 흐름 (비즈니스 중심 설명)

---

## 1. 사용자 요청

| 주체     | 설명 |
|----------|------|
| **User** | 송금 요청을 API Server로 전송 |
| **Admin** | 탐지 룰을 관리하기 위해 Admin UI를 통해 설정 |

---

## 2. API 처리 단계

| 시스템 구성 | 역할 |
|-------------|------|
| **API Server** | 송금 요청 처리 및 Kafka 이벤트 발행 |
| **Redis (잔액 선차감 - Lua)** | 송금 전 사용자 잔액을 선차감. Lua Script 사용 |
| **Redis (룰 저장)** | 탐지 룰 등록 시 저장되는 캐시 저장소 |

**흐름**
- 사용자 요청을 수신한 API Server는 Lua 스크립트로 잔액을 선차감
- 동시에 `transfer.initiated` Kafka 토픽에 이벤트 발행

---

## 3. Kafka Topic 흐름 (정상 처리 시)

| Topic 이름 | 설명 |
|------------|------|
| `transfer.initiated` | 송금 요청 발생 |
| `transfer.created` | 트랜잭션이 DB에 저장 완료됨 |
| `transfer.flagged` | 이상 탐지 결과 발생 (BLOCK, REVIEW, PASS 등) |

**정상 흐름**
```
API Server
  → Kafka: transfer.initiated
    → TxWorker consume
      → PostgreSQL INSERT 성공
      → Kafka: transfer.created
        → RiskEval consume
          → Redis TTL 상태 + 룰 조회
          → Kafka: transfer.flagged
            → WebSocket Push + Slack 알림
```

---

## 4. Kafka Consumers

| Consumer | 역할 |
|----------|------|
| **TxWorker** | `transfer.initiated` 수신 후 DB에 트랜잭션 저장 |
| **RiskEval** | `transfer.created` 수신 후 룰 평가/AI 평가 수행 |
| **SyncWorker** | DLQ 복구 전담. 실패 이벤트 기반으로 TTL/잔액/DB 복구 수행 |

---

## 5. 데이터 저장소 처리

| 구성 요소 | 설명 |
|-----------|------|
| **PostgreSQL** | 트랜잭션 로그 저장소 |
| **Redis (TTL 상태)** | 단기 송금 제약용 TTL 상태 저장 (예: 분당 1회 제한 등) |
| **Redis (룰 조회)** | RiskEval에서 룰 조회 시 사용 |
| **Redis (잔액 복구)** | 복구 필요 시 `INCRBY`로 잔액 롤백 수행 (SyncWorker가 수행) |

---

## 6. DLQ 복구 흐름 (실패 시 처리)

| 이벤트 | 설명 |
|--------|------|
| TxWorker 또는 RiskEval에서 실패 발생 | → `tx.sync_required` Kafka 토픽에 복구 요청 발행 |
| SyncWorker가 복구 처리 | DB 삽입, TTL 복구, 잔액 복구 후 Slack으로 결과 알림 |

---

## 7. 알림 시스템

| 구성 요소 | 역할 |
|-----------|------|
| **WebSocket Push** | 사용자에게 실시간 이상 탐지 결과 알림 전송 |
| **Slack 알림** | 운영자 대상 이상 탐지 및 복구 결과 통지 |

---

## 비즈니스 플로우 요약

```
User/Admin → API Server
  ↳ Redis 잔액 차감 / 룰 저장
  ↳ Kafka: transfer.initiated → TxWorker
    ↳ PostgreSQL INSERT
    ↳ Kafka: transfer.created → RiskEval
      ↳ 룰 조회, TTL 조회
      ↳ Kafka: transfer.flagged → Slack + WebSocket
        ↳ (실패 시) tx.sync_required → SyncWorker
          ↳ TTL 상태 복구 + Redis 잔액 복구 + Slack 알림
```