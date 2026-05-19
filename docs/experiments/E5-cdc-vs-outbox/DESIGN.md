# E5 — CDC(Debezium) vs Outbox Polling 처리량 실험 설계

> Phase 1 의 Outbox + Polling 은 TPS 1K 부근에서 한계가 온다는 가설을 깔고 ADR-010 을 적었다.
> 그런데 그 한계선과 CDC 의 이득은 측정되지 않은 추정치다. 이 실험은 그 두 곡선을 실제로 그어보고, Phase 3 전환의 정량 트레이드오프를 박는다.

- **Experiment ID**: E5
- **Owner**: backend
- **Date**: 2026-05-19 (설계 시점, 실측 미수행)
- **Related ADR**: [ADR-010 Phase 3](../../adr/ADR-010-async-event-publishing-evolution.md)
- **Status**: DESIGN ONLY — 실측 미수행. RESULTS 는 가설 매트릭스만 보유.

---

## 1. 배경

ADR-010 의 결정 매트릭스는 두 가지를 단정한다.

1. Outbox + Polling 은 TPS 1K 부근에서 lag 이 폭증한다.
2. CDC 로 전환하면 p99 lag 가 1~2초 대에서 100~500ms 대로 떨어진다.

두 단정은 모두 **추정**이다. E1 은 분배 알고리즘만 검증했고 ([E1 DESIGN](../E1-outbox-partitioning/DESIGN.md)), E4 는 100 TPS 부근의 정상 동작만 봤다. 1K, 5K TPS 구간에서 Outbox 가 어떻게 무너지는지, CDC 가 어디서 이득을 가져오는지 본 적이 없다.

문제는 이 두 곡선의 모양을 모르면 Phase 3 전환의 시점을 정량적으로 못 정한다는 점이다. ADR-010 은 `outbox_oldest_pending_seconds p99 > 30s` 를 critical 트리거로 적었지만, 그 임계가 진짜 Outbox 가 끝나는 지점인지, 아니면 더 일찍부터 CDC 가 이득인지 모른다.

이 실험은 두 방식의 처리량/지연 곡선을 같은 부하 조건에서 비교하고, CDC 의 운영 비용(Kafka Connect, replication slot)이 그 이득을 어디서 잡아먹는지 본다.

---

## 2. 가설

### H1 — CDC 의 p99 outbox lag ≥ 80% 감소

같은 TPS 부하에서 CDC 의 `event-publish-lag-p99` 가 Outbox Polling 의 `outbox_oldest_pending_seconds p99` 대비 80% 이상 줄어든다.

- **근거**: Outbox 는 폴링 간격(현재 1초) + 배치 처리 시간이 latency 의 하한이다. CDC 는 PostgreSQL WAL flush 직후 스트리밍이므로 polling interval 이라는 항이 사라진다.
- **측정 대상**:
  - Outbox: `outbox_oldest_pending_seconds` p99 (Prometheus gauge).
  - CDC: Debezium 의 `MilliSecondsBehindSource` p99 (JMX → Prometheus).
- **실패 시 해석**: CDC 의 publish 단의 다른 병목(Kafka Connect task queue, snapshot 단계의 lag) 이 polling interval 이득을 흡수했다는 뜻. RETRO 에서 별도 분석.

### H2 — CDC 의 DB CPU 사용량 ≥ 30% 감소

같은 TPS 부하에서 PostgreSQL primary 의 CPU 사용률이 Outbox 대비 30% 이상 낮다.

- **근거**: Outbox 의 폴링 쿼리(`SELECT … WHERE status='PENDING' … FOR UPDATE SKIP LOCKED`)가 사라진다. 빈 배치 폴링까지 합치면 인스턴스 3대 × 1초 간격 = 분당 180회 SELECT. CDC 는 WAL reader 1개만 활동한다.
- **측정 대상**:
  - `node_cpu_seconds_total{mode=~"user|system"}` (DB host, 10분 평균).
  - `pg_stat_statements` 의 `total_exec_time` 상위 10개 쿼리 비율.
- **실패 시 해석**: WAL decoding 의 CPU 비용이 폴링 절약분을 상쇄. PG 15→17 의 logical decoding 개선폭이 가설보다 작은 경우.

### H3 — CDC 의 동일 부하 처리 가능 TPS ≥ 3배 향상

`outbox_oldest_pending_seconds p99 ≤ 5s` (= ADR-010 의 Phase 2 트리거 임계) 를 유지하는 최대 TPS 가 CDC 측에서 3배 이상.

- **근거**: Outbox 는 batch_size × poll_interval × instance_count 로 처리량 상한이 결정된다. 현재 설정(1,000 × 1초 × 3) = 3,000 events/s 가 이론 상한. CDC 는 WAL 처리량 자체가 PG 처리량의 함수이므로 상한이 훨씬 높다.
- **측정 대상**: TPS 스윕(100/500/1K/5K) 에서 lag 5초 임계를 넘는 지점.
- **실패 시 해석**: Kafka Connect 의 task throughput 이 진짜 병목이라면 CDC 의 이득이 3배 미만에서 멈춤. 그 경우 Connect cluster 규모가 다음 변수.

### H4 — Kafka Connect 운영 오버헤드 정량화 (가설이 아닌 **측정 항목**)

CDC 도입의 추가 운영 비용을 숫자로 박는다.

- **측정 대상**:
  - Kafka Connect 클러스터: 3 노드 × JVM heap 2~4GB (추정) → 실측 GC pause p99, CPU 평균.
  - Connect worker 1 노드 down 시 task rebalance 시간 (chaos).
  - Connector 추가/삭제 시 REST API latency.
- **목적**: CDC 가 latency 는 줄여도 운영 부담 자체는 늘어난다는 점을 정량화. ADR-010 의 "ROI 1~2달" 추정을 검증.

### H5 — Replication slot lag 시 PostgreSQL WAL 디스크 증가 속도

CDC 의 최대 위험은 **slot 이 끊겼는데 producer 가 계속 INSERT** 되는 상황이다. WAL 이 무한 누적되어 디스크가 가득 차면 DB 가 멈춘다.

- **측정 대상**:
  - Kafka Connect 노드 전체 down → `pg_replication_slots.confirmed_flush_lsn` 이 멈춘 동안 `pg_wal` 디렉토리 증가 속도 (GB/min).
  - 부하 1K TPS 기준 디스크 가용 공간이 소진되는 예상 시간.
- **목적**: ADR-010 의 "slot 지연 시 WAL 디스크 폭증" 의 critical 임계를 숫자로. 운영 알림 임계 설계에 직접 투입.

H1·H2·H3 는 통과 기대 (CDC 의 일반적 운영 경험과 일치). H4·H5 는 **측정으로 박는 트레이드오프 항목**이다. 통과/실패 개념이 아니라 숫자가 나와야 한다.

---

## 3. 변수

| 구분 | 변수 | 값 |
|---|---|---|
| 독립 | 발행 방식 | outbox-polling / cdc-debezium |
| 독립 | 부하 TPS | 100 / 500 / 1,000 / 5,000 |
| 종속 | publish lag p50/p95/p99 | ms 단위 |
| 종속 | DB CPU % | 10분 평균 |
| 종속 | DB I/O wait % | 10분 평균 |
| 종속 | Kafka Connect heap usage | MB |
| 종속 | Kafka producer record send rate | events/s |
| 종속 | replication slot lag | LSN bytes |
| 종속 | WAL disk growth | GB/min (chaos 시) |
| 통제 | DB | PostgreSQL 17 (logical replication 활성, `wal_level=logical`) |
| 통제 | Kafka | 3.7, 3 broker, RF=3 |
| 통제 | Kafka Connect | distributed mode, 3 worker |
| 통제 | Outbox 설정 | 배치 1,000 / poll 1s / instance 3 (현재값) |
| 통제 | 하드웨어 | t3.large 급 동일 사양 6 노드 (가설) |
| 미통제 | JIT warm-up | k6 사전 ramp 5분으로 부분 보정 |
| 미통제 | OS page cache | DB 재시작으로 cold 시작 |
| 미통제 | 네트워크 지연 | 동일 AZ 내 |

미통제 변수는 결과 해석에서 명시적으로 다룬다. 특히 PG 17 의 logical decoding 성능은 PG 15 와 다른 동작을 보일 수 있어, H2 의 CPU 비교가 PG 15 운영 환경에 그대로 적용된다고 단정하지 않는다.

---

## 4. 측정 방법

### 4.1 Outbox lag

기존 `OutboxMetrics` 의 `outbox_oldest_pending_seconds` 게이지를 그대로 사용. Prometheus scrape interval 5초.

```kotlin
// OutboxMetrics.kt 에 이미 존재
meterRegistry.gauge("outbox.oldest_pending_seconds", oldestPendingSeconds) { ... }
```

### 4.2 CDC lag

Debezium PostgreSQL Connector 의 JMX 메트릭을 Prometheus JMX exporter 로 노출.

- `debezium.postgres:type=connector-metrics,context=streaming,name=<connector>` 의 `MilliSecondsBehindSource`.
- Connect worker 의 `kafka.connect:type=connect-worker-metrics` 의 task throughput.

### 4.3 DB CPU / I/O

- node_exporter: `node_cpu_seconds_total`, `node_disk_io_time_seconds_total`.
- `pg_stat_statements`: 폴링 쿼리의 `total_exec_time` 점유율.
- `pg_stat_activity`: 활성 세션 수 (Outbox 폴링이 connection pool 압박하는지).

### 4.4 Replication slot 상태

```sql
SELECT slot_name, confirmed_flush_lsn,
       pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) AS lag_bytes
FROM pg_replication_slots;
```

10초 주기 Prometheus custom exporter 로 노출. `pg_wal` 디렉토리 크기는 `node_exporter` 의 filesystem 메트릭으로.

### 4.5 부하 발생

k6 스크립트로 송금 API 에 부하. 시나리오:

```
ramp-up: 5분 (warm-up)
steady : 30분 (측정 구간)
ramp-down: 2분
```

각 TPS 레벨마다 별도 실행. 측정 구간 30분의 p99 를 결과로.

---

## 5. 실험 조건

```
[하드웨어 — 모든 노드 t3.large 가설]
  PostgreSQL primary  × 1
  Kafka broker        × 3
  Kafka Connect       × 3
  Transfer API        × 3 (송금 발행자)
  Relay (Outbox 모드) × 3
  k6 부하 발생기      × 1

[소프트웨어]
  PostgreSQL 17 (wal_level=logical, max_replication_slots=10)
  Kafka 3.7 (RF=3, min.insync.replicas=2)
  Kafka Connect 3.7 (distributed mode)
  Debezium PostgreSQL Connector 2.7
  Transfer API: JDK 17, Spring Boot 3.x
  k6: 0.50+
```

테스트 환경 셋업 예상: **1주** (Debezium 셋업 + WAL 옵션 튜닝 + JMX exporter 연결).

---

## 6. 시나리오

### 6.1 정상 부하 스윕

TPS 100 / 500 / 1,000 / 5,000 각각에 대해 outbox-polling, cdc-debezium 두 모드로 실행. 8회 × 30분 = 4시간 (인프라 셋업 별도).

### 6.2 Chaos — Kafka Connect 노드 1대 down

부하 1K TPS 정상 운영 중, Connect worker 1대를 `docker kill`. 측정:

- task rebalance 까지 소요 시간.
- 그 동안 lag 증가 폭.
- 정상화 후 backlog 소진 시간.

### 6.3 Chaos — Replication slot 정지

부하 1K TPS 정상 운영 중, **Kafka Connect 전체 중단**. PG 의 slot 이 더 이상 confirmed_flush_lsn 을 진행시키지 못함. 측정:

- `pg_wal` 디렉토리 증가 속도 (GB/min).
- 디스크 가용 공간 100GB 가정 시 소진 예상 시간.
- 그 동안 transfer API 의 INSERT latency 변화.

---

## 7. 예상 결과

H1·H2·H3 통과 시 예상 매트릭스:

| TPS | Outbox p99 lag | CDC p99 lag | DB CPU (Outbox) | DB CPU (CDC) |
|---|---|---|---|---|
| 100 | 약 1초 | 약 100ms | 15% | 8% |
| 500 | 약 2초 | 약 150ms | 35% | 18% |
| 1,000 | 약 5초 (임계 근접) | 약 200ms | 65% | 30% |
| 5,000 | 처리 불가 (lag 폭증) | 약 500ms | 95%+ saturate | 55% |

가장 큰 불확실성은 **TPS 5,000 구간에서 CDC 의 한계가 어디인가**이다. Confluent 가 공개한 PostgreSQL Connector 벤치마크는 단일 노드 기준 약 10K events/s 까지 처리 가능하다고 기록한다. 그러나 그건 단순 INSERT 스트림이고, transfer_events 의 row 크기 / payload 크기에 따라 한참 다를 수 있다. 5,000 TPS 에서 CDC 가 흔들리면 그 자체가 발견이다.

---

## 8. 성공/실패 기준

다음을 모두 만족하면 Phase 3 전환 트리거 도달 시 즉시 채택 (별도 ADR 작성 불필요, ADR-010 으로 충분).

1. H1 통과 — 80% lag 감소.
2. H2 통과 — 30% CPU 감소.
3. H3 통과 — 3배 TPS 처리.
4. H5 의 WAL 증가 속도가 1K TPS 기준 1GB/min 이하 (즉, 100GB 가용 시 100분 윈도우 확보 가능).

다음 중 하나라도 발생하면 ADR-010 Phase 3 를 재설계.

- **CDC 의 lag 가 1K TPS 부근에서 Outbox 와 차이가 50% 미만** — polling interval 이외 항이 지배.
- **Connect 노드 down 시 rebalance > 60초** — 가용성이 Outbox 보다 나쁨.
- **WAL 증가 속도 > 5GB/min** — 운영 윈도우 너무 짧음. fallback 으로 outbox 병행 필수.

---

## 9. 범위 밖

- **CDC schema evolution**: Avro/Schema Registry 도입은 별도 실험. 본 실험은 JSON envelope 만 사용.
- **Initial snapshot 단계**: 운영 전환 시 1회성 비용. 본 실험은 streaming 정상 상태만 측정.
- **다중 Connector 운영**: 토픽별 Connector 분리 전략은 후속.
- **PG primary failover 시 slot 재생성**: HA 시나리오는 별도 실험.
- **CDC + 기존 Outbox 병행 운영 기간**: 점진적 전환의 운영 비용은 [RETRO](./RETRO.md) 의 E5-3, E5-4 로 분리.
- **메시지 포맷 변경의 컨슈머 영향**: Envelope `{before, after}` 포맷으로 변경 시 FDS 컨슈머의 코드 변경 범위는 별도 PoC.

범위를 좁힌 이유는 변수 분리다. CDC 의 streaming 성능과 schema evolution 의 운영 부담은 다른 축의 문제이므로 한 실험에서 섞으면 결과 해석이 불가능해진다.

---

## 10. 다이어그램

- 비교 매트릭스: [diagrams/comparison-matrix.mmd](./diagrams/comparison-matrix.mmd) — TPS 축의 lag 곡선 비교.

---

## 11. 관련 문서

- 결정 사항: [ADR-010 Phase 3](../../adr/ADR-010-async-event-publishing-evolution.md)
- 진화 로드맵: [EVOLUTION-ROADMAP.md](../../EVOLUTION-ROADMAP.md)
- 선행 실험: [E1 (분배)](../E1-outbox-partitioning/), [E4 (load baseline)](../E4-load-baseline/)
- 운영 메트릭: `services/transfer/instances/api/.../observability/OutboxMetrics.kt`
- 알림 룰: `monitoring/prometheus-alerts.yml`
- 외부 표준:
  - [Debezium PostgreSQL Connector — Performance](https://debezium.io/documentation/reference/stable/connectors/postgresql.html#postgresql-performance)
  - [Confluent — Outbox to CDC evolution](https://www.confluent.io/blog/messaging-microservices/)
  - [PostgreSQL — Logical Replication](https://www.postgresql.org/docs/17/logical-replication.html)
