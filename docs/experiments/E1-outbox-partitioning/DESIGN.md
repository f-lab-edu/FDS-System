# E1 — Outbox Relay 파티셔닝 실험 설계

> 단일 Outbox 테이블을 3대의 Relay 인스턴스가 동시에 폴링할 때, `FOR UPDATE SKIP LOCKED` 만으로는 락 경합은 풀어도 분배 균등성은 풀지 못한다.
> 이 실험은 그 가설을 측정으로 검증하고, MOD 기반 파티셔닝의 트레이드오프를 정량화한다.

- **Experiment ID**: E1
- **Owner**: backend
- **Date**: 2025-12-15
- **Related ADR**: [ADR-003](../../adr/ADR-003-relay-partitioning-by-modulo.md)
- **Related Retro**: [Phase 1 Retrospective](../../retrospective/phase-1-outbox-and-partitioning.md)
- **Commit lineage**: `9bf9af6` (single→multi thread) → `82eedde` (thread pool tuning) → `1ba6a6c` (Spring Batch 이관)

---

## 1. 배경

ADR-002 의 Transactional Outbox 패턴을 단일 Relay 인스턴스로 운영하다 송금 TPS 가 늘면서 발행 지연이 누적되었다. 3대로 스케일아웃하면서 처음에는 `FOR UPDATE SKIP LOCKED` 한 줄로 락 경합을 풀 수 있다고 봤다. 그런데 측정값이 그 가정을 흔들었다. 한 인스턴스가 빈 배치를 1,658회 반복 조회하고 있었다.

이 실험은 "SKIP LOCKED 만으로 멀티 인스턴스 분배가 자동으로 풀린다"는 처음 가설이 어디서 깨지는지, 그리고 MOD 파티셔닝이 그 자리를 대신할 때 어떤 대가를 치르는지를 확인한다.

---

## 2. 가설

### H1 — DB 쿼리 ≥ 90% 감소

MOD 파티셔닝 적용 시 총 DB 쿼리 수가 단순 SKIP LOCKED 대비 90% 이상 줄어든다.

- **근거**: 각 인스턴스가 자기 파티션만 조회하므로, 다른 인스턴스가 이미 처리해버린 행을 다시 SELECT 하는 빈 배치가 사라진다.
- **측정 대상**: `total query count = Σ(per-instance query count)` (Before/After 각 1회).

### H2 — 균등 분배 33.3% ± 1%

각 인스턴스의 처리 비율이 33.3% 기준 ± 1% 안에 들어온다.

- **근거**: Snowflake event_id 가 단조 증가이므로 MOD 3 분포가 통계적으로 균등하다. 락 경합이 사라져 "빠른 인스턴스 독식" 현상도 사라진다.
- **측정 대상**: `Instance N 처리 비율 = N 이 처리한 건수 / 전체 건수 × 100`.

### H3 — 총 처리 시간 ≤ 15% 증가 (감수 가능)

MOD 연산 오버헤드로 인해 총 처리 시간은 늘지만, 단발 측정에서 15% 이하 증가에 머문다.

- **근거**: `WHERE status = 'PENDING' AND MOD(event_id, 3) = N` 은 인덱스를 깨끗하게 활용하지 못한다 (PG 함수 인덱스 미적용). 빈 배치 폴링이 사라지는 효과보다 행 단위 MOD 평가 비용이 단발 처리에서 우세할 수 있다.
- **측정 대상**: `total elapsed ms = max(per-instance elapsed)` (3 인스턴스 동시 실행).
- **실패 시 대응**: 15% 를 넘으면 함수 인덱스 도입 또는 파티션 수 재설계를 후속 실험으로 분리한다.

H1·H2 는 통과 기대. H3 는 **트레이드오프 감수 영역**으로 둔다. 즉 H3 가 실패해도 H1·H2 가 통과하면 채택할 가능성이 있다.

---

## 3. 변수

| 구분 | 변수 | 값 |
|---|---|---|
| 독립 | 파티셔닝 전략 | Before(단순 SKIP LOCKED) / After(MOD 파티셔닝) |
| 종속 | 총 DB 쿼리 수 | Σ per-instance query count |
| 종속 | 처리 분포 | 각 인스턴스 처리 건수 / 전체 |
| 종속 | 총 처리 시간 | max(per-instance elapsed ms) |
| 종속 | 빈 배치 횟수 | rows_returned = 0 인 SELECT 호출 수 |
| 통제 | 총 이벤트 수 | 10,000 건 |
| 통제 | 배치 크기 | 1,000 건 |
| 통제 | 인스턴스 수 | 3 대 |
| 통제 | DB | PostgreSQL 15 (Docker, 단일 인스턴스) |
| 통제 | 하드웨어 | Apple M1 Pro (12 Core), 32GB RAM |
| 통제 | 실행 방식 | 3 인스턴스 동시 시작 (멀티스레드 동기 실행) |
| 미통제 | JIT warm-up | 없음 (단발 실행) |
| 미통제 | OS scheduling jitter | 없음 |
| 미통제 | PostgreSQL buffer pool 상태 | 매 실행마다 다를 수 있음 |

미통제 변수는 결과 해석에서 명시적으로 다룬다. 특히 단발 측정이라 통계적 신뢰구간이 없다는 한계가 H3 결과 해석에 가장 크게 영향을 준다.

---

## 4. 측정 방법

### 4.1 DB 쿼리 카운터

각 Relay 인스턴스 내부에 `AtomicLong` 카운터를 둔다. `claimBatch()` 또는 `claimBatchByPartition()` 호출 직전 increment.

```kotlin
// PartitioningPerformanceComparisonTest.kt 의 측정 지점
val queryCount = AtomicLong(0)
// 매 폴링 사이클마다
queryCount.incrementAndGet()
val rows = repository.claimBatchByPartition(partition, totalPartitions, limit)
if (rows.isEmpty()) emptyBatchCount.incrementAndGet()
```

빈 배치 횟수는 `rows.isEmpty()` 시 별도 카운터로 분리해 둔다 (의외 발견의 근거가 된다).

### 4.2 처리 시간 타이머

각 인스턴스가 첫 폴링부터 PENDING=0 확인까지 `System.nanoTime()` 으로 측정한다. 3 인스턴스의 elapsed 중 최댓값을 "총 처리 시간"으로 정의한다 (마지막 인스턴스가 끝나야 실험이 끝난 것이므로).

### 4.3 처리 분포

PG 에 `instance_id` 컬럼을 추가하고 발행 완료 시 기록하는 방식 대신, 인스턴스가 내부적으로 처리한 건수를 카운트해 비교한다. 실험 종료 후 `SELECT COUNT(*) FROM transfer_events WHERE status='SENT'` 로 누락 여부를 교차 검증한다.

---

## 5. 실험 조건

```
OS: macOS 14.x
CPU: Apple M1 Pro (12 Core)
RAM: 32GB
PostgreSQL: 15 (Docker, 기본 설정)
Kafka: 3.7 (Docker)
JVM: Temurin 17

테스트 설정:
  총 이벤트: 10,000건 (테스트 시작 전 INSERT)
  배치 크기: 1,000건
  Relay 인스턴스: 3대 (동일 JVM 내 별도 스레드로 시뮬레이션)
  실행 방식: CountDownLatch 로 3 인스턴스 동시 start
  실행 횟수: 각 시나리오 1회 (단발 측정)
```

테스트 코드: `services/transfer/instances/transfer-relay/src/test/kotlin/.../PartitioningPerformanceComparisonTest.kt`

---

## 6. 시나리오

### Before — 단순 SKIP LOCKED

```sql
SELECT * FROM transfer_events
WHERE status = 'PENDING'
ORDER BY event_id
LIMIT 1000
FOR UPDATE SKIP LOCKED
```

3 인스턴스가 동일 쿼리를 던진다. 빠른 인스턴스가 락을 먼저 잡고 batch 를 가져간다. 락을 놓친 인스턴스는 빈 결과를 받고 즉시 다음 polling 으로 넘어간다.

### After — MOD 파티셔닝

```sql
-- Instance N (N ∈ {0,1,2})
SELECT * FROM transfer_events
WHERE status = 'PENDING'
  AND MOD(event_id, 3) = :N
ORDER BY event_id
LIMIT 1000
FOR UPDATE SKIP LOCKED
```

각 인스턴스가 자기 파티션의 행만 조회한다. SKIP LOCKED 는 같은 인스턴스 내부의 동시성 보호 용도로만 남는다.

---

## 7. 예상 결과

| 지표 | Before 예상 | After 예상 |
|---|---|---|
| 총 DB 쿼리 수 | 수백~수천 (빈 배치 폭증) | 10~30 회 (필요한 만큼만) |
| 처리 분포 | 불균등 (한 인스턴스 ≤ 25%) | 33.3% ± 1% |
| 총 처리 시간 | 18~20초 | 19~22초 |
| 빈 배치 횟수 | 수백~수천 | 0~10 회 |

가장 큰 불확실성은 "Before 의 빈 배치가 얼마나 발생할 것인가" 다. 단순 SKIP LOCKED 로 3 인스턴스가 경합하면 분배가 운에 좌우되므로, 한 인스턴스가 거의 모든 폴링을 헛돌 가능성이 있다. 예상치 못한 큰 격차가 나오면 그 자체로 의미 있는 발견이다.

---

## 8. 실패 기준

다음 중 하나라도 발생하면 실험을 실패로 간주하고 설계를 다시 본다.

1. **누락**: After 시나리오에서 `SELECT COUNT(*) WHERE status='SENT'` 가 10,000 미만.
2. **중복 처리**: 같은 event_id 가 두 인스턴스에서 모두 SENT 로 마킹됨. MOD 분배 + SKIP LOCKED 가 의도대로 동작하지 않은 것.
3. **H1 미달**: After 의 총 쿼리 수가 Before 의 10% 를 초과. 즉 90% 감소 미달.
4. **H2 미달**: After 의 한 인스턴스가 33.3% ± 1% 범위를 벗어남.
5. **H3 대폭 실패**: After 의 총 처리 시간이 Before 의 130% 이상. 단순한 MOD 오버헤드 이상의 구조적 문제가 있다는 신호.

1·2 는 정합성 문제이므로 실험 결과를 채택할 수 없다. 3·4·5 는 정합성은 유지되지만 가설이 깨진 것이므로 RETRO 에 기록하고 후속 실험으로 옮긴다.

---

## 9. 범위 밖

이 실험에서 다루지 않는 것을 명시한다.

- **인스턴스 수 변화 (N=5, 7, 10)**: E1-1 로 분리.
- **파티션 수 ≠ 인스턴스 수 시나리오**: E1-2 로 분리.
- **Polling interval 튜닝**: E1-3 으로 분리.
- **장기 실행 안정성 (24시간 부하)**: E4 (load-baseline) 로 분리.
- **Spring Batch 통합 후의 성능**: 본 실험은 Spring Batch 이관 이전의 ThreadPool 기반 구현에서 측정.
- **PostgreSQL 함수 인덱스 효과**: 후속 실험으로 분리. 현재는 미적용.

범위를 좁힌 이유는 단발 측정의 한계 때문이다. 변수를 동시에 여러 개 흔들면 어느 변수가 효과를 만들었는지 분리하기 어렵다.

---

## 10. 다이어그램

- 가설 설계: [diagrams/hypothesis.mmd](./diagrams/hypothesis.mmd)
- 결과 비교: [diagrams/results.mmd](./diagrams/results.mmd) (실험 후 RESULTS 에서 인용)

---

## 11. 관련 문서

- 실측 데이터 원본: [docs/etc/performance-test.md](../../etc/performance-test.md)
- 결정 사항 기록: [ADR-003](../../adr/ADR-003-relay-partitioning-by-modulo.md)
- 회고: [Phase 1 Retrospective](../../retrospective/phase-1-outbox-and-partitioning.md)
- 결정 타임라인: [DECISIONS-TIMELINE Stage 5](../../DECISIONS-TIMELINE.md)
