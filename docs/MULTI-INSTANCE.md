# MULTI-INSTANCE — 멀티 인스턴스에서 발생할 수 있는 엣지케이스

> 단일 인스턴스에선 안 보이지만 인스턴스 수가 ≥ 2 가 되는 순간 드러나는 함정들.
> 각 항목은 "어떤 가정이 깨지나" + "어떻게 방어하나" 형식.

---

## 1. Snowflake ID 충돌

### 1.1 문제

`common-domain` 의 `SnowflakeIdGenerator` 는 `SNOWFLAKE_NODE_ID` 환경변수로 node 식별. 같은 node_id 로 인스턴스가 두 개 뜨면 **같은 timestamp + sequence 에서 동일 ID 생성** → PK 충돌.

### 1.2 현재 상태

- `docker-compose.yml` 의 `transfer-api` 는 `SNOWFLAKE_NODE_ID: 1` 하드코딩.
- transfer-api 를 멀티 인스턴스로 확장하려면 각 인스턴스마다 다른 node_id 가 강제되어야 하는데, **자동 할당 메커니즘 없음**.

### 1.3 깨지는 시나리오

- Kubernetes 의 Deployment 가 transfer-api 를 replicas=3 으로 띄우면 3개 다 `SNOWFLAKE_NODE_ID=1`.
- 같은 ms 에 트랜잭션 생성 → PK 충돌 → `DataIntegrityViolationException` → 송금 실패.

### 1.4 방어

- StatefulSet + pod ordinal 을 node_id 로 매핑 (`pod-0 → 1`, `pod-1 → 2`).
- 또는 외부 분산 락(Redis/ZK) 으로 node_id 동적 할당.
- 또는 UUID v7 도입 (ID 충돌 자체를 없앰; ADR-001 의 trade-off).
- 운영 전 **반드시** 정책 결정. 현재 미결정 → LIMITATIONS.

---

## 2. Outbox MOD 파티셔닝의 인스턴스 수 불일치

### 2.1 문제

`transfer-relay` 의 쿼리:

```sql
WHERE MOD(event_id, partition_count) = instance_id
```

`partition_count` 가 N 일 때 인스턴스가 정확히 N 대 떠야 모든 파티션이 커버. 인스턴스 수와 partition_count 가 다르면 일부 event 가 영원히 처리 안 됨.

### 2.2 깨지는 시나리오

- partition_count=3 설정. 실제 인스턴스는 4대 → instance_id=3 인 인스턴스는 처리할 이벤트가 없음 (모든 row 가 MOD 3 = 0/1/2).
- partition_count=3 설정. 실제 인스턴스는 2대 (instance 0, 1) → MOD 3 = 2 인 이벤트가 영원히 stuck.

### 2.3 스케일링 도중의 더 큰 함정

스케일 아웃 1대 → 3대:

1. 1대 운영 중 (partition_count=1, instance_id=0). 모든 이벤트 처리.
2. 2대 추가. 새 설정 `partition_count=3, instance_id=1/2`.
3. **기존 인스턴스의 설정 안 바뀌면** `partition_count=1, instance_id=0` → 여전히 모든 이벤트 잡아감.
4. 새 인스턴스 둘은 자기 파티션에 해당하는 이벤트가 없어 idle.
5. 또는 반대로 설정 전파 race condition.

### 2.4 방어

- partition_count 와 인스턴스 수를 **동시에** 변경하는 단일 배포 단위 보장.
- 변경 직전 outbox PENDING 을 0 으로 비우고 진행 (drain).
- Kafka 컨슈머 그룹 위임 모델로 교체 (정적 파티셔닝 자체를 없앰). ADR-003 의 대안 항목.

### 2.5 현재 미검증

이 문제들은 **실제 시뮬레이션 0회** (LIMITATIONS 2.1).

---

## 3. Kafka Streams `application.id` / `instance.id`

### 3.1 application.id

`fds-api` 의 `application.yml`:

```yaml
application.id: fds-stream-processor
```

**같은 application.id 인스턴스끼리 consumer group 형성** + State Store 의 changelog 토픽 공유. 따라서 fds-api 를 N 대 띄우면 자동으로 작업 분배.

### 3.2 함정: instance.id 미설정

`num.stream.threads=2` 인 환경에서 N 인스턴스를 띄우면:

- 총 stream thread 수 = N × 2.
- Streams 가 자동 rebalance.

**하지만** stateful task (State Store) 의 인스턴스 이전이 발생 — RocksDB changelog replay 비용. 인스턴스 추가 시 5~수십 분 lag.

### 3.3 standby replica 부재

`num.standby.replicas` 미설정(default 0). 인스턴스 1대가 죽으면 그 task 의 State Store 는 **다른 인스턴스에서 처음부터 replay**. lag 큼.

### 3.4 방어

- `num.standby.replicas: 1` 설정으로 hot standby.
- Kubernetes 라면 `instance.id` 를 pod 이름으로 고정해 stateful 정체성 유지.
- changelog 토픽 retention 보수적 설정.

---

## 4. 동일 계좌 동시 송금 (DB 락 경합)

### 4.1 문제

서로 다른 인스턴스의 두 송금 요청이 같은 계좌에 동시 도달:

```
Instance A: validate sender_X → ...
Instance B: validate sender_X → ...
```

### 4.2 현재 방어

`TransactionService.loadUsers` 가 `findByAccountNumberWithLock` 으로 **PostgreSQL row-level 비관 락** 사용. 한 트랜잭션이 락 잡으면 다른 트랜잭션은 대기. 정렬된 락 획득 순서로 데드락 회피.

### 4.3 한계

- **인스턴스 수가 늘면 락 대기 시간 증가**. p99 latency 폭증 가능.
- 분산 환경에서 DB 락 자체가 병목.

### 4.4 더 좋은 방향

- Redis Redlock 같은 분산 락 (계좌별).
- Account sharding (특정 계좌는 특정 인스턴스로 라우팅).
- 그러나 이건 다른 종류의 복잡도. 현재 DB 락이 충분히 잘 동작 — 측정 후 결정.

---

## 5. 분산 트레이싱 sampling 불일치

### 5.1 문제

`management.tracing.sampling.probability: 1.0` 가 인스턴스별 설정. 인스턴스마다 다른 비율로 sampling 하면 **traceId 가 일관성 잃음** — 한 송금의 일부는 추적, 일부는 누락.

### 5.2 방어

- 모든 인스턴스에 동일 설정.
- 동적 샘플링(예: 에러 100%, 정상 10%) 도입 시 외부 설정 (Spring Cloud Config / Consul) 으로 일관.

### 5.3 현재 상태

모든 인스턴스 1.0. 운영 부하에선 폭증.

---

## 6. Redis 단일 키 hot spot

### 6.1 문제

`daily:transfer:{userId}:{yyyyMMdd}` — 같은 사용자의 송금이 다른 인스턴스에서 동시 발생하면 **Redis 단일 키에 모든 INCRBY 가 집중**.

### 6.2 방어

- Redis INCRBY 자체가 atomic 이라 정합성 OK.
- 처리량 측면에선 단일 노드 Redis 의 throughput 한계 (~100K ops/s) 까지 안전.
- 100만 TPS 시나리오라면 user_id sharding + Redis Cluster.

### 6.3 다른 함정 — 캐시 stampede

캐시 미스 시 0 으로 시작. 인스턴스 여러 곳에서 동시 처음 호출 시 모두 0 으로 시작 → 한도 초과 검증 missing. 

방어: **이 시점의 캐시 stampede 는 비즈니스 측면 안전 방향(fail-open)** 이라 OK. 다만 **첫 송금 후 모든 인스턴스의 후속 호출은 정상**.

---

## 7. 이벤트 발행 멀티 인스턴스의 멱등성

### 7.1 문제

transfer-api 동기 발행 + transfer-relay 비동기 발행이 동시에 같은 outbox row 를 처리하면 **Kafka 에 2회 발행** → FDS 가 2회 분석.

### 7.2 현재 방어

- outbox 의 `FOR UPDATE SKIP LOCKED` 로 race 방지.
- transfer-api 의 동기 send 성공 시 `status=SENT` 갱신 → relay 가 안 잡음.

### 7.3 멱등성

- FDS 의 `fraud_detections.event_id` 에 unique constraint 미부착(LIMITATIONS). 중복 처리 시 INSERT 충돌.
- Kafka exactly-once-v2 미적용. consumer 재처리 시 중복.

### 7.4 방어

- `fraud_detections` 에 `UNIQUE(event_id)` + `ON CONFLICT DO NOTHING`.
- `processing.guarantee=exactly_once_v2`.
- 후속 PR.

---

## 8. 컨테이너 graceful shutdown

### 8.1 문제

`kill -9` 또는 짧은 `SIGTERM grace period` 로 인스턴스 종료 시:

- transfer-api: 진행 중 `@Transactional` abort → 롤백. **하지만 outbox 동기 발행 직후 죽으면**? 트랜잭션은 commit, outbox 에 row 는 없는 상태. 이벤트 유실.
- transfer-relay: outbox row 를 `SENDING` 상태로 잡아둔 채 죽으면 다른 인스턴스가 못 잡음 (skip-locked 의 락 자체는 풀리지만 status 가 SENDING 이라 쿼리 조건 불만족).
- FDS Streams: rebalance 트리거. 짧은 시간 lag.

### 8.2 방어

- Spring Boot `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`. 현재 미설정.
- transfer-relay 의 `SENDING` → `PENDING` 자동 복구 스케줄러 미구현.
- Kubernetes preStop hook 으로 30초 대기.

### 8.3 현재 상태

미설정. LIMITATIONS 2.1.

---

## 9. 메트릭 라벨 폭증

### 9.1 문제

`http_server_requests_seconds_count{uri="/api/transfers/123"}` 처럼 path variable 이 label 로 들어가면 cardinality 폭증 → Prometheus OOM.

### 9.2 방어

- Spring Actuator 의 `metrics.tags` 에 `uri` 가 path variable 을 자동 templating (`/api/transfers/{id}`).
- 검증 — `prometheus` curl 로 unique label 수 확인.

### 9.3 현재 상태

확인 안 함.

---

## 10. 운영 가이드 체크리스트

신규 인스턴스 추가 전:

- [ ] node_id 충돌 없는가 (Snowflake)?
- [ ] transfer-relay 라면 partition_count / instance_id 매칭?
- [ ] application.yml 의 sampling probability 일치?
- [ ] heap 설정 (-Xmx) 인스턴스 수만큼 모니터링 capacity 확보?
- [ ] Kafka 토픽 partition 수가 새 인스턴스 수 ≥?
- [ ] traceId MDC 손실 회귀 없는가 (`@Async`, Kafka)?
- [ ] graceful shutdown 설정?

---

## 11. 정직히 인정

위 모든 시나리오 **로컬 docker compose 에서 시뮬레이션 미수행**.

- transfer-api 의 멀티 인스턴스 시뮬레이션 0회 (단일 인스턴스 단일 SNOWFLAKE_NODE_ID).
- transfer-relay 멀티 인스턴스는 시뮬레이션 했으나 **failover/스케일링 도중 시나리오는 미검증**.
- FDS-API 멀티 인스턴스 미시뮬레이션.

후속 PR 의 chaos testing(P3 Toxiproxy / Litmus) 으로 검증한다.

---

## 관련 문서

- [LIMITATIONS.md](LIMITATIONS.md) 2.1 — Relay 멀티 인스턴스 운영 검증 부재
- [FAILURE-MODES.md](FAILURE-MODES.md) 3.2~3.5 — 인스턴스 down 시나리오
- [ADR-003](adr/ADR-003-relay-partitioning-by-modulo.md) — MOD 파티셔닝 결정
- [DECISIONS-TIMELINE.md](DECISIONS-TIMELINE.md) Stage 5 — Relay 진화
