# ADR-003: Relay 멀티 인스턴스의 MOD 기반 파티셔닝

- **Status**: Accepted
- **Date**: 2025-12-15
- **Deciders**: Backend Lead
- **Tags**: scaling, outbox, partitioning, postgresql, performance

---

## Context

ADR-002 의 Transactional Outbox 패턴을 단일 Relay 인스턴스로 운영하던 중, 송금 TPS 가 증가하면서 Outbox 발행 지연이 누적되었다. 자연스러운 다음 단계는 Relay 의 스케일아웃이었다.

### 단순 스케일아웃의 함정

3 대로 늘렸을 때 **각 인스턴스가 동일한 쿼리를 실행**하게 된다.

```sql
-- 3대 모두 똑같은 쿼리
UPDATE transfer_events
SET status = 'SENDING'
WHERE event_id IN (
    SELECT event_id FROM transfer_events
    WHERE status = 'PENDING'
    ORDER BY event_id
    LIMIT 500
    FOR UPDATE SKIP LOCKED
)
RETURNING ...
```

`FOR UPDATE SKIP LOCKED` 덕에 락 충돌 자체는 일어나지 않는다. 하지만 **빈 배치 조회 폭발**과 **불균등 분배** 문제가 발생했다.

### 실측 데이터 (성능 테스트, 10,000 건 / 3 인스턴스)

```
총 처리 시간: 18,360ms
처리 분포:
  Instance 0: 3,000건 (30.0%)
  Instance 1: 3,000건 (30.0%)
  Instance 2: 2,000건 (20.0%)  <- 불균등

DB 쿼리 수:
  Instance 0:    12회
  Instance 1:     3회
  Instance 2: 1,658회  <- 빈 배치 반복 조회
  총 쿼리: 1,673회
```

원인 분석:

- **빠른 인스턴스가 독식**: I/O 지연이 약간이라도 적은 인스턴스가 락을 먼저 잡고 batch 를 가져간다.
- **느린 인스턴스의 빈 배치**: 락을 놓친 인스턴스는 "PENDING 없음" 을 반환받고 즉시 다음 polling 으로 넘어가 또 같은 쿼리를 던진다. 이 사이클이 1,658회 반복되었다.
- **DB 부하 폭증**: 의미 없는 SELECT 가 DB 의 buffer pool / lock manager 를 점유한다.

> 참고: [partitioning-strategy.md](../etc/partitioning-strategy.md), [performance-test.md](../etc/performance-test.md)

---

## Decision

**각 Relay 인스턴스에 고유한 `instanceId` 를 부여하고, `MOD(event_id, totalInstances) = instanceId` 조건으로 처리 범위를 분할한다.**

### 1. 분할 원리

Snowflake `event_id` 가 단조 증가하므로 MOD N 분할 시 균등 분배가 통계적으로 보장된다.

```text
이벤트: 100, 101, 102, 103, 104, 105, 106, ...
MOD 3 = 0: 102, 105, 108, ...   <- Instance 0
MOD 3 = 1: 100, 103, 106, ...   <- Instance 1
MOD 3 = 2: 101, 104, 107, ...   <- Instance 2
```

### 2. SQL

```sql
UPDATE transfer_events
SET status = 'SENDING', updated_at = :now
WHERE event_id IN (
    SELECT event_id
    FROM transfer_events
    WHERE status = 'PENDING'
      AND next_retry_at <= :now
      AND MOD(event_id, :totalPartitions) = :partition
    ORDER BY event_id
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
RETURNING event_id, aggregate_id, payload, headers
```

`MOD(event_id, :totalPartitions) = :partition` 한 줄이 핵심.

### 3. 환경 변수로 파티션 주입

```yaml
app:
  outbox:
    relay:
      instanceId: ${RELAY_INSTANCE_ID:0}
      totalInstances: ${RELAY_TOTAL_INSTANCES:1}
```

Docker Compose 에서 각 인스턴스에 다른 `RELAY_INSTANCE_ID` 를 주입한다.

```yaml
transfer-relay-0:
  environment:
    RELAY_INSTANCE_ID: 0
    RELAY_TOTAL_INSTANCES: 3
transfer-relay-1:
  environment:
    RELAY_INSTANCE_ID: 1
    RELAY_TOTAL_INSTANCES: 3
transfer-relay-2:
  environment:
    RELAY_INSTANCE_ID: 2
    RELAY_TOTAL_INSTANCES: 3
```

---

## Consequences

### Positive

- **DB 쿼리 99% 감소**: 1,673회 → 17회. Instance 2 의 빈 배치 1,658회가 0회로 줄었다.
- **균등 분배**: 30%/30%/20% → 33.3%/33.3%/33.3%.
- **락 경합 제거**: 인스턴스끼리 같은 행을 노리지 않으므로 `SKIP LOCKED` 가 발동되지 않는다.
- **선형 확장**: 인스턴스 N 배 → 처리량 N 배. 평시 3 대, 피크 10 대로 확장 가능.
- **예측 가능성**: 각 인스턴스의 처리량이 균등하므로 모니터링 alert 임계치 설정이 단순해진다.

### Negative

- **MOD 연산 오버헤드**: 처리 시간이 18,360ms → 20,690ms 로 약 11% 느려졌다. 인덱스 사용 가능성 저하 (함수 인덱스 미사용 시 full scan). **다만 빈 배치가 사라져 DB 전체 부하는 99% 감소했으므로 실 프로덕션에서는 트레이드오프 이득이 크다.**
- **인스턴스 수 변경 시 일시 정지 필요**: `totalInstances` 가 인스턴스마다 다르면 누락/중복이 발생한다. 스케일링 시 절차:
  1. 모든 PENDING 이벤트 소진 대기
  2. 모든 인스턴스 중단
  3. `totalInstances` 일괄 변경
  4. 재기동
- **단일 파티션 장애의 영향**: Instance 2 가 죽으면 `MOD 3 = 2` 인 이벤트가 정체된다. K8s liveness probe + 자동 재시작으로 완화.

### Neutral

- **함수 인덱스 추가 고려**: PostgreSQL 에서 `CREATE INDEX ON transfer_events ((event_id % 3))` 가 가능하지만 `totalInstances` 가 바뀌면 무용지물. 현재는 미적용.
- **Hash 분포 가정**: Snowflake ID 가 단조 증가라 MOD 분포가 거의 완벽하다. UUID 였다면 다른 전략 필요.

---

## Alternatives Considered

### A. 해시 기반 파티셔닝

```sql
WHERE HASHTEXT(aggregate_id) % :totalPartitions = :partition
```

- **장점**: ID 가 단조 증가가 아닐 때도 균등 분배.
- **단점**:
  - PostgreSQL `HASHTEXT` 는 표준 SQL 이 아니고 vendor lock-in.
  - 함수 인덱스 필요 → totalPartitions 변경 시 인덱스 재생성.
- **기각 사유**: Snowflake ID 의 단조 증가 특성으로 MOD 만으로 충분.

### B. Consistent Hashing

- **장점**: 인스턴스 추가/삭제 시 영향 받는 키 비율이 1/N 만큼.
- **단점**:
  - SQL 쿼리에 표현이 어려움 (애플리케이션 레벨 라우팅 필요).
  - 가상 노드 관리 등 운영 복잡도 증가.
- **기각 사유**: Outbox 는 “모든 이벤트가 한 번씩 처리되면 끝” 인 batch 성격. Stateful 라우팅 불필요.

### C. Kafka Consumer Group 위임

Outbox 자체를 없애고 송금 시 직접 Kafka 로 발행하되, Consumer Group 의 partition assignment 를 활용.

- **장점**: Kafka 가 자동 리밸런싱. 운영 비용 절감.
- **단점**:
  - **ADR-002 의 Dual Write Problem 미해결**.
  - Outbox 의 본질은 “DB 커밋과 묶이는 보장” 인데, 이 대안은 그 보장을 포기.
- **기각 사유**: Outbox 의 목적과 충돌.

### D. Pessimistic Locking + Coordinator

Zookeeper / etcd 로 “현재 누가 발행 담당인지” 락을 잡고 그 인스턴스만 모든 행 처리.

- **장점**: 분배 로직 불필요.
- **단점**:
  - **단일 인스턴스만 활성 → 스케일아웃 의미 상실**.
  - Coordinator 의존성 추가.
- **기각 사유**: 스케일아웃 목적과 충돌.

### E. Spring Batch (Phase Next)

> 커밋 `1ba6a6c`: single instance multiThread 전략의 relay-server 를 spring batch 로 이관

- **현재 상태**: 단일 인스턴스 멀티스레드 전략은 Spring Batch 로 이관 중. 멀티 인스턴스 + 파티셔닝은 여전히 본 ADR 의 방식.
- **장점**: 재시작 복원성, Job Repository 기반 추적.
- **단점**: Batch Job 의 폴링 주기가 길어 실시간성 손해.
- **결합 방식**: Spring Batch 의 Step 안에서 `claimBatchByPartition()` 을 사용한다. 즉 Spring Batch 는 **실행 프레임워크** 일 뿐, 분배 로직은 본 ADR 의 MOD 방식을 그대로 사용한다.

---

## Trade-offs

| 축 | MOD (채택) | Hash | Consistent Hashing | Coordinator |
|---|---|---|---|---|
| **균등 분배** | 통계적으로 보장 (Snowflake) | 균등 | 균등 | N/A |
| **DB 쿼리 부하** | 매우 낮음 | 중 (함수 인덱스 필요) | 중 | 낮음 (단일 발행) |
| **인스턴스 추가 영향** | 모든 키 재분배 | 모든 키 재분배 | 1/N 만 재분배 | 영향 없음 |
| **운영 복잡도** | 저 (환경변수만) | 중 | 고 (가상노드 관리) | 고 (Coordinator) |
| **실시간성** | 최상 (1초 polling) | 최상 | 최상 | 단일 인스턴스 한계 |
| **vendor lock-in** | 없음 | `HASHTEXT` 사용 시 있음 | 없음 | Coordinator 의존 |

---

## Performance Evidence

> [performance-test.md](../etc/performance-test.md) 의 실측 결과 인용

| 지표 | Before (단순 SKIP LOCKED) | After (MOD 파티셔닝) | 개선율 |
|---|---|---|---|
| **DB 쿼리 수** | 1,673회 | 17회 | **99% 감소** |
| **처리 분포** | 30%, 30%, 20% | 33.3%, 33.3%, 33.3% | **균등 달성** |
| **락 경합** | 높음 | 없음 | **완전 제거** |
| **총 처리 시간** | 18,360ms | 20,690ms | -11% (느림) |
| **선형 확장성** | 깨짐 | 보장 | **개선** |

처리 시간이 약간 느려진 것은 MOD 연산 오버헤드 때문이지만, **DB 쿼리 부하 99% 감소**가 본질적 이득이다.

---

## References

### 코드 경로

- Repository 인터페이스: `services/transfer/application/.../TransferEventsOutboxRepository.kt`
- 파티셔닝 SQL 구현: `services/transfer/infra/src/main/kotlin/.../TransferEventsOutboxJdbcRepository.kt`
- Relay 메인 로직: `services/transfer/instances/transfer-relay/src/main/kotlin/.../TransferOutboxRelay.kt`
- OutboxRelayConfig: `services/transfer/instances/transfer-relay/src/main/kotlin/.../OutboxRelayConfig.kt`
- 성능 테스트: `services/transfer/instances/transfer-relay/src/test/kotlin/.../PartitioningPerformanceComparisonTest.kt`
- 상세 문서: `docs/etc/partitioning-strategy.md`, `docs/etc/performance-test.md`

### 외부 자료

- PostgreSQL Documentation, "SELECT ... FOR UPDATE SKIP LOCKED" (9.5+)
- Vlad Mihalcea, "How SKIP LOCKED can improve PostgreSQL polling"
- David Karger et al., "Consistent Hashing and Random Trees" (1997) — 대안 검토용

---

## Related ADRs

- ADR-002: Transactional Outbox Pattern (본 파티셔닝의 전제)
- ADR-005: Redis Daily Limit Cache (Snowflake ID 의 시간 순 특성 공유)
