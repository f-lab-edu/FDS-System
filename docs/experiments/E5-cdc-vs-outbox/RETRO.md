# E5 — CDC vs Outbox 회고

> 측정 없이 ADR-010 의 결정 매트릭스를 적었다. 그 부담을 정직하게 적고, 그 부담을 줄이기 위한 후속 실험 분기를 분리한다.

- **Experiment ID**: E5
- **Date**: 2026-05-19 (RETRO 작성 시점, E5 실측 이전)
- **Owner**: backend
- **Related ADR**: [ADR-010](../../adr/ADR-010-async-event-publishing-evolution.md)

---

## 1. 측정 없이 ADR 적은 부담

ADR-010 의 Phase 2~5 는 모두 추정에 기반한다. 그 중 Phase 3 (CDC) 는 가장 큰 운영 변화이면서 정량 근거가 가장 약하다.

부담의 구체적 형태:

- **트리거 임계가 진짜인지 모른다.** `outbox_oldest_pending_seconds p99 > 30s` 가 critical 인 이유는 "어느 정도면 운영팀이 못 견딘다" 는 직관에 가깝다. 실측에서 5초가 진짜 경계일 수도, 60초까지 견딜 수도 있다.
- **CDC 의 이득이 진짜인지 모른다.** "p99 1s → 100ms" 는 Debezium 의 일반 벤치마크에서 빌려온 숫자다. 우리 환경의 row 크기, payload, downstream 컨슈머 처리량을 반영하지 않았다.
- **운영 비용이 진짜인지 모른다.** Kafka Connect 3 노드 × 2~4GB heap 은 운영 사례에서 빌려온 추정이다. 우리 트래픽의 throughput 에서는 단일 노드로 충분할 수도 있다.

이 세 개의 갭은 트래픽이 1K TPS 임계를 칠 때 동시에 부담으로 돌아온다. 그 시점에 결정해야 하는 운영자는 추정만 보고 1~2달 작업을 시작해야 한다.

ADR-010 을 적을 때 가장 고민했던 부분은 "추정에 기반한 의사결정 경로를 박는 것이 가치 있는가" 였다. 결론은 가치 있다. **추정이라도 명문화되어 있으면 트리거 시점에 0 부터 토론하지 않아도 된다.** 다만 추정임을 RESULTS / RETRO 에서 정직하게 드러내야 한다. 그렇지 않으면 "ADR 에 적혀 있으니 맞다" 가 되어버린다.

---

## 2. 후속 실험 분기

E5 를 한 번에 다 돌리는 것은 셋업 비용 / 자원 / 우선순위 모두에서 부담이다. 단계별로 쪼갠다.

### E5-1 — Outbox 천장 측정 (Phase 2 튜닝 후 도달 가능한 TPS)

**목적**: Phase 2 의 파라미터 튜닝 (배치 5,000, polling 200ms, instance 5~7) 으로 도달 가능한 실제 TPS 상한 측정. CDC 도입 결정 전에 "Outbox 의 진짜 천장" 을 알아야 그 위에서 CDC 의 이득이 의미 있는지 판단된다.

**범위**:
- 발행 방식: outbox-polling 만 (CDC 미포함).
- TPS: 1,000 / 2,000 / 3,000 / 5,000 스윕.
- 변수: 배치 사이즈, polling interval, instance 수.

**산출**: Phase 2 튜닝 매트릭스. 어느 파라미터 조합에서 어디까지 안정 처리 가능한지 곡선.

**선행 조건**: 없음. 기존 코드만으로 측정 가능.

### E5-2 — Debezium PoC (단일 노드, 100 TPS 시작)

**목적**: Debezium 셋업 자체를 검증. 정상 동작, 운영 가시성, 메시지 포맷의 downstream 영향만 확인. 처리량 비교는 다음 실험.

**범위**:
- Debezium PostgreSQL Connector 2.7 단일 worker.
- 부하: 100 TPS (기존 sandbox 자원 안에서 가능).
- 측정: lag, JMX 메트릭 노출, Connector REST API 동작.

**산출**: 셋업 매뉴얼, JMX → Prometheus 노출 설정, FDS 컨슈머의 envelope 메시지 호환 확인.

**선행 조건**: PG `wal_level=logical` 변경 (운영 환경에선 PG 재시작 필요).

### E5-3 — 점진적 전환 시나리오 (한 토픽씩 마이그레이션)

**목적**: 운영 중인 outbox 를 CDC 로 한 번에 바꾸는 것은 위험하다. 토픽 단위로 옮기면서 두 경로가 같은 데이터를 발행하는 기간 동안의 충돌 / 중복 / 누락 동작 검증.

**범위**:
- transfer_events 의 일부 type 만 CDC 발행, 나머지는 outbox.
- 컨슈머 측 멱등성 (event_id 기반 dedup) 동작 확인.
- 양쪽 발행 정지 시 데이터 누락 검출 메트릭.

**산출**: 마이그레이션 플레이북. 어느 순서로 어떤 토픽을 옮기는지, 롤백 조건은 무엇인지.

**선행 조건**: E5-2 통과.

### E5-4 — Kafka Connect 장애 시 fallback

**목적**: CDC 전면 전환 후 Connect 가 죽으면 outbox 가 없으니 발행이 멈춘다. 그 위험을 어떻게 막을지의 정량 평가.

**범위**:
- 시나리오 A: Connect 전체 down 동안 outbox 가 backup 발행 (이중 발행).
- 시나리오 B: Connect 전체 down 시 transfer API 가 outbox 모드로 강등 (운영 토글).
- 시나리오 C: Connect down 동안 INSERT 만 받고 발행은 멈춤 (lag 누적 허용).

**산출**: 각 시나리오의 추가 운영 비용 / 위험 매트릭스. ADR-010 의 phase 3 → phase 4 의 중간 단계로 박을지 결정.

**선행 조건**: E5-3 통과.

---

## 3. 빅테크 운영 참고

E5 의 셋업과 결정 매트릭스를 박을 때 참고할 외부 표준.

- **Confluent** — [Outbox to CDC Evolution](https://www.confluent.io/blog/messaging-microservices/) 의 단계별 전환 패턴. Phase 1~3 의 일반화된 케이스.
- **LinkedIn Engineering** — [Kafka Transactional Producer](https://engineering.linkedin.com/blog/2018/06/exactly-once-stream-processing-with-kafka-2). Phase 4 의 사전 검토 자료.
- **Netflix** — [DBLog: A Generic Change-Data-Capture Framework](https://netflixtechblog.com/dblog-a-generic-change-data-capture-framework-69351fb9099b). 초기 snapshot 과 streaming 분리 패턴. E5-3 의 점진적 마이그레이션 설계에 인용.
- **Debezium** — [PostgreSQL Connector 운영 가이드](https://debezium.io/documentation/reference/stable/connectors/postgresql.html). 특히 `Slot Configuration` 과 `Replication Slot Lag` 섹션은 H5 의 위험 평가에 직접 사용.
- **Martin Kleppmann** — Designing Data-Intensive Applications Ch.11. Outbox / CDC / Event Sourcing 의 비교를 일반화한 텍스트. ADR-010 의 phase 매트릭스가 이 책의 분류 체계와 정합.

---

## 4. 다음에 같은 ADR 을 적을 때

E5 의 부담을 통해 배운 것.

- **추정 ADR 은 RESULTS 가 비어있는 상태로도 가치 있다.** 단, RESULTS 가 "추정" 임을 정직하게 적어야 한다. 빈 RESULTS 는 추정을 결정으로 둔갑시킨다.
- **트리거 메트릭이 운영 환경에 이미 떠 있어야 한다.** `OutboxMetrics` 가 이미 노출되어 있어서 Phase 2~3 트리거가 실시간으로 감지 가능하다. 트리거 메트릭이 ADR 에만 적혀 있고 운영에 없으면 그 ADR 은 사실상 dead letter.
- **셋업 비용이 큰 실험은 ADR 시점에 미리 분기해 둔다.** E5 를 한 번에 돌리려는 시도는 자원 / 우선순위 모두에서 실패한다. RETRO §2 의 분기는 ADR-010 작성 시점에 이미 합의되어 있어야 했다.

---

## 5. 관련 문서

- [DESIGN.md](./DESIGN.md)
- [RESULTS.md](./RESULTS.md)
- [ADR-010](../../adr/ADR-010-async-event-publishing-evolution.md)
- [ADR-009 — Experiment Driven Decisions](../../adr/ADR-009-experiment-driven-decisions.md) — 실험 없이 결정한 ADR 을 어떻게 다뤄야 하는지의 원칙.
- [EVOLUTION-ROADMAP.md](../../EVOLUTION-ROADMAP.md)
