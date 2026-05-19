# ADR-010: 트래픽 레벨별 비동기 이벤트 발행 전략 진화

- **Status**: Accepted (Phase 1) / Proposed (Phase 2~5 전환 트리거)
- **Date**: 2026-05-19
- **Supersedes**: 부분적으로 ADR-002 의 단정 (Outbox 가 영구 해답이라는 암묵적 가정)
- **Tags**: scaling, kafka, outbox, cdc, debezium, evolution

---

## Context

ADR-002 / ADR-003 은 **현 시점에 Transactional Outbox + MOD 파티셔닝이 정답** 이라고 결정했다. 그러나 이건 **현재 트래픽(< 1K TPS) 기준**의 결정이다. 트래픽이 10K, 100K TPS 로 진화하면 Outbox Polling 자체가 병목이 된다.

빅테크 운영 표준은 **트래픽 레벨별 다른 패턴**을 적용한다.

| TPS 레벨 | 일반적 선택 | 한계 |
|---|---|---|
| < 1K | Transactional Outbox + Polling | DB 부하 (현재) |
| 1K~10K | Outbox + Polling 튜닝 / 또는 CDC 도입 | Polling 빈도 vs DB 부하 trade |
| 10K~100K | CDC (Debezium) — WAL/binlog 스트림 | Kafka Connect 클러스터 운영 |
| 100K+ | Kafka Transactional Producer / Event Sourcing | 분산 트랜잭션 / Read model 분리 |

**현재 프로젝트는 1번 단계만 구현**. 단계 전환의 **트리거 메트릭/기준이 명문화되지 않으면 잘못된 시점에 잘못된 도구를 도입**한다. 이 ADR 가 그 표준을 박는다.

## Decision

비동기 이벤트 발행 진화 경로를 5 phase 로 명문화. 각 phase 의 **전환 트리거 메트릭**을 정의.

### Phase 1 — Outbox + Polling Relay (현재)

- 구현: `transfer_events` 테이블 + transfer-relay 의 Spring Batch + MOD 파티셔닝.
- 트래픽 가정: ≤ 1K TPS, P99 outbox lag ≤ 5s.
- 운영 비용: DB 폴링 부하 (현재 17회/배치).

### Phase 2 — Polling 튜닝 (1K~5K TPS)

- 같은 outbox 구조 유지, 다음 파라미터 튜닝:
  - 배치 사이즈: 1,000 → 5,000~10,000.
  - 폴링 간격: 1초 → 200ms.
  - Relay 인스턴스 수: 3 → 5~7.
  - DB connection pool: 10 → 20.
- **트리거**: `outbox_pending_count` p95 > 500 OR `outbox_oldest_pending_seconds` p95 > 5s.
- 비용: 빈 배치 폴링 증가 → DB CPU 증가.
- 이득: 운영 도구 변경 없음, 작업 ROI 최고.

### Phase 3 — CDC (Debezium PostgreSQL Connector)

- 구현 전환: outbox 테이블 → CDC Source Connector 가 WAL 스트림 → Kafka Connect → Kafka.
- 폴링 제거. **거의 실시간** (PostgreSQL WAL flush 주기 ~10ms).
- **트리거**: Phase 2 의 폴링 간격을 100ms 이하로 줄여도 lag 안 잡힐 때 OR DB CPU > 70% 의 50% 이상이 outbox 폴링 차지.
- 비용:
  - Kafka Connect 클러스터 운영 (별도 JVM).
  - Schema evolution (Avro/Debezium schema history topic).
  - logical replication slot 관리 (slot 지연 시 WAL 디스크 폭증).
  - 메시지 포맷 변경 — outbox row 가 아닌 `Envelope { before, after }` 형식.
- 이득:
  - Polling 비용 0.
  - p99 latency 1~2초 → 100~500ms.
  - 운영 가시성 (Debezium JMX + Kafka Connect REST).

### Phase 4 — Kafka Transactional Producer + Outbox 제거

- DB write 와 Kafka send 를 같은 Kafka transaction.
- `producer.send` 가 트랜잭션 commit 시 동시 발행.
- **트리거**: CDC 도 부족할 정도의 latency 요구 (P99 < 100ms) OR 비즈니스 요구상 outbox 적재 자체가 비용.
- 비용:
  - **Kafka 의 `isolation.level=read_committed` 컨슈머만 사용**. 컨슈머 lag 증가.
  - 처리량 ↓ (transactional producer 의 commit overhead).
  - DB 와 Kafka 의 분산 트랜잭션 일관성 추론 어려움.
- 이득: Outbox 테이블 자체 제거 → DB 부담 해소.

### Phase 5 — Event Sourcing / 별도 Event Log

- DB 가 Event Store. 송금 트랜잭션 자체가 이벤트 시퀀스.
- **트리거**: 100K+ TPS, 시간 여행 쿼리 / 감사 요구 / 다중 read model.
- 비용:
  - 패러다임 전체 변경.
  - Eventual consistency.
  - read model 빌드/갱신 비용.
- 이득:
  - 진정한 분산.
  - 감사/규제 대응 우수.

### Serverless 대안 (Fargate + EventBridge + Lambda)

언급된 Fargate + Jenkins cron 배치 옵션은 **phase 2 의 변형**으로 해석한다.

- AWS Fargate 로 Relay 컨테이너 운영 + EventBridge cron → Lambda 가 폴링 트리거.
- 비용:
  - **Cold start** (Lambda 매번 JVM 부팅).
  - DB connection 관리 (Lambda 의 connection 풀 부재 — RDS Proxy 필수).
  - 분산 락 / 멱등성 부담 (여러 Lambda 동시 실행).
- 이득:
  - 인스턴스 관리 0.
  - 비용 효율 (트래픽 적을 때).
- **결정**: 현재 트래픽에선 Fargate 가 **비용 효율은 있지만 latency / 운영 가시성에서 손해**. 도입 보류. 트래픽이 매우 spiky (e.g., 야간 0 TPS / 낮 10K) 일 때 재검토.

## Consequences

### Positive

- 트래픽 레벨별 **명시적 결정 매트릭스**. 잘못된 시점의 잘못된 도구 도입 방지.
- 전환 트리거가 **메트릭(`outbox_pending_count`, `outbox_oldest_pending_seconds`)**으로 표현 — 사람의 직관이 아닌 데이터로 결정.
- 각 phase 의 비용/이득이 정량화.

### Negative

- Phase 3+ 는 미구현. ADR 가 "이상" 인 상태 — 실측 0.
- CDC 도입 비용은 가설. 실제 운영 부담은 PoC 후 측정.
- Phase 5 는 패러다임 변경이라 ADR 만으로 결정 불가.

### Neutral

- 단계 전환 시점은 이 ADR 의 트리거 메트릭이 켜질 때 별도 ADR 로 결정.
- 운영 환경(AWS/GCP/온프렘) 에 따라 Serverless 대안 우선순위 다름.

## Alternatives Considered

| 대안 | 평가 | 채택 여부 |
|---|---|---|
| **Outbox 영구 사용** | TPS 한계 모름. 미래 결정의 시야 차단 | ✗ |
| **처음부터 CDC** | 트래픽 작을 때 과한 운영 부담 | ✗ |
| **처음부터 Kafka Tx Producer** | DB ↔ Kafka 의 강결합, 학습 부담 | ✗ |
| **Serverless 전면** | Cold start + connection 문제 | △ (Phase 2 변형으로 옵션) |
| **단계별 진화 + 전환 트리거 메트릭** | 명시적 의사결정 경로 | ✓ |

## Trade-offs (정량화)

각 전환의 비용 vs 이득:

| 전환 | 추정 비용 | 추정 이득 | ROI 기준 |
|---|---|---|---|
| P1 → P2 (튜닝) | DB CPU +20% | TPS 한계 5배 | 작업 1주 |
| P2 → P3 (CDC) | Kafka Connect 운영 (+1 클러스터) | p99 latency 1s → 100ms | 작업 1~2달 |
| P3 → P4 (Tx Producer) | 컨슈머 lag +30%, throughput -20% | Outbox 제거 → DB 부담 -40% | 작업 2~3달 |
| P4 → P5 (Event Sourcing) | 패러다임 전환, read model 구축 | 100K+ TPS, 감사 가능 | 작업 6달+ |

## 전환 트리거 메트릭 (운영 가이드)

| 메트릭 | 임계 | 권장 액션 |
|---|---|---|
| `outbox_pending_count` p95 > 500 sustained 1h | warning | Phase 2 튜닝 검토 |
| `outbox_oldest_pending_seconds` p95 > 5s | warning | Phase 2 튜닝 즉시 |
| `outbox_oldest_pending_seconds` p99 > 30s | critical | Phase 3 CDC PoC 시작 |
| DB CPU % consumed by outbox polling > 30% | warning | Phase 3 CDC 검토 |
| `kafka_producer_record_send_total` rate > 10K/s sustained | info | Phase 4 Tx Producer 검토 |
| 감사/규제 요구 + read model 다중화 | n/a | Phase 5 Event Sourcing 검토 |

알림 룰: `monitoring/prometheus-alerts.yml` 에 phase 전환 트리거 추가 (이 PR 의 후속).

## 실험 연결

- [E1 Outbox 파티셔닝](../experiments/E1-outbox-partitioning/) — Phase 1 의 정당화.
- E5 CDC vs Outbox 처리량 (예정) — Phase 3 의 PoC 설계.

## References

- 코드: `services/transfer/instances/transfer-relay/`, `services/transfer/instances/api/.../observability/OutboxMetrics.kt`
- 관련 ADR: [ADR-002](ADR-002-transactional-outbox-pattern.md), [ADR-003](ADR-003-relay-partitioning-by-modulo.md)
- 진화 로드맵: [EVOLUTION-ROADMAP.md](../EVOLUTION-ROADMAP.md)
- 모니터링: [prometheus-alerts.yml](../../monitoring/prometheus-alerts.yml), [grafana/.../transentia.json](../../monitoring/grafana/provisioning/dashboards/transentia.json)
- 외부:
  - [Debezium PostgreSQL Connector](https://debezium.io/documentation/reference/stable/connectors/postgresql.html)
  - [Confluent — Outbox Pattern → CDC 진화](https://www.confluent.io/blog/messaging-microservices/)
  - [Martin Kleppmann — Designing Data-Intensive Applications, Ch 11](https://dataintensive.net/) — Stream Processing
  - [LinkedIn Engineering — Kafka Transactional Producer](https://engineering.linkedin.com/blog/2018/06/exactly-once-stream-processing-with-kafka-2)
