# E5 — CDC vs Outbox Polling 결과

> **Status: NOT YET RUN.**
> 본 문서는 설계만 존재하는 시점의 RESULTS 다. 실측 숫자는 모두 "(추정)" 으로 표기한다.
> 가설 기반 매트릭스는 ADR-010 의 결정 매트릭스를 정량적으로 풀어 적는 용도이며, 실측 후 본 문서를 통째로 덮어쓰는 것이 정상 운영 흐름이다.

- **Experiment ID**: E5
- **Run Date**: — (미수행)
- **Owner**: backend
- **Hypotheses**: [DESIGN.md](./DESIGN.md) 의 H1~H5
- **Related ADR**: [ADR-010 Phase 3](../../adr/ADR-010-async-event-publishing-evolution.md)

---

## 1. 요약 — 왜 실측이 아직 없는가

ADR-010 은 Phase 3 의 트리거 메트릭만 명문화했고, CDC 의 정량 이득은 "추정" 수준으로 두었다. 그 추정을 측정으로 박을 실험이 E5 다. 지금 실측이 없는 이유는 단순하다.

1. **Debezium / Kafka Connect 셋업이 미완.** 설계상 1주 작업이 필요하다 (DESIGN §5).
2. **부하 시뮬레이션 환경 자원 부족.** k6 발생기 + Kafka 3 broker + Connect 3 worker + DB + Relay 3 + API 3 = 최소 13 노드. 현재 sandbox 의 docker-compose 로는 1K TPS 부하 자체가 발생되지 않는다.
3. **우선순위.** Phase 1 의 운영 검증(E1, E4, ELK 통합) 이 아직 충분치 않다. 실측 없는 추정에 인프라를 먼저 쓰는 것은 ROI 가 안 맞는다.

이 세 가지가 풀리지 않은 상태로 측정을 강행하면 결과의 신뢰도가 떨어진다. 그래서 본 문서는 **가설 매트릭스 + 부분 검증 가능 항목 + 갭이 결정에 미치는 영향**만 정리한다.

---

## 2. 예상 결과 매트릭스 (가설 기반, 실측 아님)

DESIGN §2 의 H1~H3 가 통과한다는 가정에서 작성. 모든 숫자에 "(추정)" 을 붙인다.

### 2.1 부하별 publish lag (p99)

| TPS | Outbox p99 lag (추정) | CDC p99 lag (추정) | 비고 |
|---|---|---|---|
| 100 | 약 1초 | 약 100ms | Outbox 도 polling interval 1초가 하한 |
| 500 | 약 2초 | 약 150ms | Outbox 의 빈 배치 폴링이 줄면서 안정 |
| 1,000 | 약 5초 | 약 200ms | **Phase 2 트리거 임계 (ADR-010 의 5초 warning)** |
| 5,000 | lag 발산 (처리 불가) | 약 500ms | Outbox 의 이론 상한 3,000 events/s 를 넘어섬 |

**해석 포인트 (추정):**

- TPS 100 구간은 두 방식 모두 충분한 여유. 이 구간에서 CDC 로 가는 것은 latency 가 아닌 다른 이유(스키마 통합, 운영 표준화) 가 있어야 정당화.
- TPS 1,000 구간이 **결정의 경계**다. Outbox 가 5초 임계를 친다는 것은 ADR-010 의 Phase 3 critical 트리거 (`p99 > 30s`) 의 1/6 지점. 즉 운영상 30초 임계가 켜지기 전에 5초 임계에서 이미 CDC 검토가 시작되어야 한다.
- TPS 5,000 구간에서 Outbox 의 발산은 polling interval × batch size × instance count 의 곱 한계 때문. 이 지점에서는 Phase 2 의 튜닝(배치 5,000, polling 200ms, instance 5~7) 이 먼저 시도되어야 하고, 그래도 안 잡히면 Phase 3 이다.

### 2.2 DB CPU (10분 평균)

| TPS | Outbox CPU% (추정) | CDC CPU% (추정) | 비고 |
|---|---|---|---|
| 100 | 15% | 8% | 폴링 비용이 base CPU 의 절반 |
| 500 | 35% | 18% | |
| 1,000 | 65% | 30% | **Phase 3 검토 임계 (ADR-010 의 outbox 폴링 > 30%)** |
| 5,000 | 95%+ saturate | 55% | Outbox 의 폴링이 query plan cache 까지 압박 |

H2 의 "30% 감소" 가설은 TPS 1,000 부근에서 가장 잘 보인다 (65% → 30%, -53%). 더 낮거나 더 높은 TPS 에서는 이득이 작거나(낮음) 측정이 불안정(높음) 하다.

### 2.3 Replication slot lag 시 WAL 증가 (H5)

부하 1K TPS 기준 (추정):

| 시나리오 | WAL 증가 속도 | 100GB 디스크 소진 예상 |
|---|---|---|
| Connect 정상 | < 50MB/min | n/a |
| Connect 1대 down | < 100MB/min | n/a (남은 2대가 흡수) |
| **Connect 전체 down** | 약 1~3GB/min (추정) | **30~100분** |

100분 윈도우는 운영팀이 깨어서 대응할 수 있는 시간이다. 30분이면 야간 호출 대응이 빠듯하다. 실측이 30분 이하로 나오면 fallback 으로 outbox 병행 운영이 필수가 된다.

---

## 3. 부분 검증 가능 항목 (실측 아님, 인용/유추)

E5 를 실제로 돌리지 않아도 결정의 일부는 다른 데이터로 받쳐진다.

### 3.1 Outbox 의 현재 성능 곡선 (E1, E4 인용)

- E1: 10,000 건 배치 처리 시 MOD 파티셔닝으로 약 19~22초 소요. 곧 약 450~520 events/s 단발 처리량.
- E4 (load-baseline): 100 TPS 부근에서 lag 폭주 없이 안정. 그 위의 측정은 아직 없음.

이 두 데이터는 **TPS 100~500 구간의 Outbox 동작은 안전하다**는 정도까지만 보장한다. 그 위는 외삽.

### 3.2 Debezium 의 일반 벤치마크 (외부 인용)

- Confluent 의 공식 [PostgreSQL Connector 성능 가이드](https://debezium.io/documentation/reference/stable/connectors/postgresql.html#postgresql-performance) 는 단일 worker 기준 약 10K events/s 처리 가능을 기록.
- LinkedIn 의 CDC 운영 사례는 100K+ events/s 까지 클러스터 확장으로 처리.
- Netflix 의 DBLog 패턴은 CDC + snapshot 분리로 초기 적재 비용 분리.

이 인용은 **CDC 가 일반적으로 1K TPS 정도는 단일 워커로 충분히 처리한다**는 정도를 받쳐준다. 그러나 transfer_events 의 row 크기, payload 형태, downstream 컨슈머 throughput 까지 포함한 우리 환경의 숫자는 실측이 있어야 한다.

### 3.3 Phase 1 의 운영 메트릭 (현재 가용)

`OutboxMetrics` 가 노출하는 `outbox_oldest_pending_seconds`, `outbox_pending_count` 는 운영 환경에 이미 떠 있다. 트래픽이 증가하면서 이 두 값의 곡선이 휘는 시점을 보면 **외삽 없이도** Phase 2 / Phase 3 트리거가 자동으로 잡힌다. 즉 E5 의 실측이 늦어도 **현장 트리거가 먼저 켜질 것**이라는 안전망이 있다.

---

## 4. 갭이 결정에 미치는 영향

실측이 없다는 것이 운영에 어떤 위험을 만드는가.

### 4.1 즉시 위험 — 없음

현재 트래픽은 ADR-010 의 Phase 1 범위 (< 1K TPS) 안이다. Outbox 가 충분히 동작하고 있고, `outbox_oldest_pending_seconds` p99 가 5초 임계 근처도 가지 않는다. E5 가 없어서 지금 결정을 못 하는 일은 없다.

### 4.2 단기 위험 (3~6개월) — 낮음

트래픽이 Phase 2 트리거 (`p95 > 500 pending` 또는 `p95 > 5s lag`) 를 친다고 가정해도, 그 다음 단계는 Phase 2 의 튜닝이지 CDC 가 아니다. Phase 2 튜닝은 E5 의 결과 없이도 진행 가능하다 (파라미터 조정만이므로).

### 4.3 중기 위험 (6~12개월) — 보통

Phase 2 튜닝으로도 안 잡히는 시점이 오면 CDC 결정이 필요한데, 그 시점에 E5 가 없으면 **추정만으로 1~2달 작업을 시작**하는 셈이 된다. 이게 가장 큰 위험이다. 그래서 RETRO 에 적은 후속 실험 E5-1, E5-2 는 Phase 2 진입 시점에 동시 시작되어야 한다.

### 4.4 장기 위험 (12개월+) — 데이터로 막힌다

Phase 1 의 운영 메트릭이 1년 단위로 누적되면 Outbox 의 한계가 외삽이 아닌 실측 곡선으로 보인다. 그 시점에는 E5 의 합성 부하 실험이 일부 불필요해진다 (운영 데이터 자체가 진실). E5 의 가치는 그 시점이 오기 **전에** 결정을 미리 박을 수 있게 한다는 점이다.

---

## 5. 다음 액션

DESIGN 의 8.성공/실패 기준은 실측 후 채워진다. 그 전까지는 다음만 명확히 한다.

1. ADR-010 의 Phase 3 트리거가 켜지면 즉시 E5 의 H1·H2·H3 실측 실행. 셋업 1주 + 측정 1주.
2. 셋업 부담을 줄이기 위해 RETRO 의 **E5-2 (Debezium 단일 노드 PoC, 100 TPS)** 를 선제 실행. 정상 동작과 운영 가시성만 확인.
3. `monitoring/prometheus-alerts.yml` 의 Phase 3 트리거 알림 룰은 ADR-010 의 임계로 우선 박아두고, 실측 후 보정.

---

## 6. 관련 문서

- [DESIGN.md](./DESIGN.md) — 가설/측정/시나리오 정의.
- [RETRO.md](./RETRO.md) — 측정 없는 ADR 의 부담과 후속 실험 분기.
- [ADR-010 Phase 3](../../adr/ADR-010-async-event-publishing-evolution.md) — 전환 결정의 매트릭스.
- [E1 RESULTS](../E1-outbox-partitioning/RESULTS.md) — Outbox 분배의 정량 결과.
- [E4 RESULTS](../E4-load-baseline/RESULTS.md) — 100 TPS 구간 정상 동작.
