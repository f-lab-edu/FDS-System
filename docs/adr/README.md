# Architecture Decision Records

> 이 디렉터리는 시스템의 굵직한 결정들을 추적한다. 결정의 시점·계기·대안 비교·버린 옵션을 한 곳에서 본다.

## ADR 인덱스

| # | 제목 | Status | 핵심 결정 |
|---|---|---|---|
| [001](ADR-001-hexagonal-architecture-pragmatic-adoption.md) | 헥사고날 아키텍처의 실용적 채택 | Accepted | domain/application/infra/instances 4 분할. 순수성 80/20 절충 |
| [002](ADR-002-transactional-outbox-pattern.md) | Transactional Outbox 패턴 | Accepted | 송금 트랜잭션과 Kafka 발행을 outbox 테이블로 분리 |
| [003](ADR-003-relay-partitioning-by-modulo.md) | Relay 멀티 인스턴스의 MOD 파티셔닝 | Accepted | `MOD(event_id, n) = instanceId` + SKIP LOCKED |
| [004](ADR-004-kafka-streams-for-pattern-detection.md) | 패턴 탐지를 위한 Kafka Streams 도입 | Accepted | stateless + 10분 windowedBy 듀얼 파이프라인 |
| [005](ADR-005-redis-daily-limit-cache.md) | Redis 일일 송금 한도 캐시 | Accepted | `INCRBY` + TTL `daily:transfer:{userId}:{yyyyMMdd}` |
| [006](ADR-006-elk-observability.md) | ELK 기반 관측성 + AccessLog 분리 | Accepted | Filebeat input 2 분리, `app-logs-*` / `access-logs-*` |
| [007](ADR-007-distributed-tracing-context-propagation.md) | 분산 트레이싱 컨텍스트 전파 | Accepted | Micrometer Observation + `ContextPropagatingTaskDecorator` + `TracingTransformer` |
| [008](ADR-008-build-logic-convention-plugins.md) | buildSrc → build-logic 이관 | Accepted | Gradle included build + convention plugin |
| [009](ADR-009-experiment-driven-decisions.md) | 실험 주도 의사결정 | Accepted | `docs/experiments/EN-*/` 4종 문서 표준 (DESIGN/RESULTS/RETRO/diagrams) |
| [010](ADR-010-async-event-publishing-evolution.md) | 비동기 이벤트 발행 진화 | Accepted (P1) / Proposed (P2~5) | 트래픽 레벨별 Outbox→CDC→TxProducer→ES 진화, 전환 트리거 메트릭 |
| [011](ADR-011-ml-anomaly-score-evolution.md) | ML anomaly score 진화 | Accepted (P1) / Proposed (P2~4) | `AiScoreProvider` 의 NoOp→Heuristic→Statistical→Isolation Forest→ES kNN 4단계 |

## ADR 작성 규칙

1. **파일명**: `ADR-NNN-kebab-case-title.md` (NNN 은 3자리)
2. **필수 섹션**: Status / Date / Tags / Context / Decision / Consequences / Alternatives Considered / Trade-offs / References
3. **상태 흐름**: `Proposed` → `Accepted` / `Rejected` / `Superseded by ADR-XXX`
4. **단정적 문체**: "~할 수 있다" 보다 "~로 결정했다". 결정자가 책임진다.
5. **대안 표**: 검토한 옵션 모두 적기. **버린 이유를 명시**. 면접에서 가장 자주 묻는 부분.
6. **코드 인용**: 가능한 경우 `services/.../File.kt:LL` 형태로 인용. 결정과 코드의 연결고리.
7. **LIMITATIONS 링크**: 이 결정이 남긴 부채를 LIMITATIONS 문서로 연결.

## 관련 문서

- [의사결정 Timeline](../DECISIONS-TIMELINE.md) — ADR 간의 시간 축
- [LIMITATIONS](../LIMITATIONS.md) — 각 ADR 이 남긴 갭
- [Phase 별 회고](../retrospective/README.md) — ADR 의 사후 평가
- [EVOLUTION-ROADMAP](../EVOLUTION-ROADMAP.md) — 트래픽 진화 5단계

## ADR ↔ 실험 ↔ 회고 정합 매트릭스 (ADR-009 규칙)

| ADR | 관련 실험 | 관련 회고 | 관련 문서 |
|---|---|---|---|
| ADR-001 헥사고날 | — (설계 결정) | [phase-1](../retrospective/phase-1-outbox-and-partitioning.md), [phase-2](../retrospective/phase-2-observability-and-cache.md), [phase-3](../retrospective/phase-3-streaming-and-fds.md) | [현실적인 헥사고날](../etc/현실적인%20헥사고날%20아키텍처와의%20타협.md) |
| ADR-002 Outbox | [E1](../experiments/E1-outbox-partitioning/) | [phase-1](../retrospective/phase-1-outbox-and-partitioning.md) | [ADR-010](ADR-010-async-event-publishing-evolution.md) supersedes |
| ADR-003 MOD 파티셔닝 | [E1](../experiments/E1-outbox-partitioning/) | [phase-1](../retrospective/phase-1-outbox-and-partitioning.md) | [performance-test](../etc/performance-test.md), [partitioning-strategy](../etc/partitioning-strategy.md) |
| ADR-004 Kafka Streams | — (E6 후속 후보) | [phase-3](../retrospective/phase-3-streaming-and-fds.md) | [kafkaStream](../etc/kafkaStream.md) |
| ADR-005 Redis 캐시 | [E3](../experiments/E3-circuit-breaker/) (NOT YET RUN) | [phase-2](../retrospective/phase-2-observability-and-cache.md) | [LIMITATIONS 1.2/2.3](../LIMITATIONS.md) |
| ADR-006 ELK | — (운영 데이터 부재) | [phase-2](../retrospective/phase-2-observability-and-cache.md) | [observability-setup](../etc/observability-setup%20-%20ELK_Stack.md) |
| ADR-007 분산 트레이싱 | — | [phase-2](../retrospective/phase-2-observability-and-cache.md) | [W3C Trace Context](../etc/W3C%20Trace%20Context.md) |
| ADR-008 build-logic | — (인프라 결정) | — | [build-logic 블로그](../etc/%5Bblog%5D%20buildSrc%20를%20걷어내고%20build-logic%20을%20도입해보자.md) |
| ADR-009 실험 주도 | 모든 E1~E5 의 메타 | 모든 phase 회고의 메타 | [EXPERIMENTS README](../experiments/README.md) |
| ADR-010 발행 진화 | [E1](../experiments/E1-outbox-partitioning/), [E5](../experiments/E5-cdc-vs-outbox/) (NOT YET RUN) | [phase-1](../retrospective/phase-1-outbox-and-partitioning.md) | [EVOLUTION-ROADMAP](../EVOLUTION-ROADMAP.md) |

검색 효율: 실험/회고/문서 → ADR 역방향은 각 파일의 References 섹션 또는 이 표를 참조.

## 후속 ADR 후보

- ADR-009: DLQ Worker 정책 (재시도 횟수, 백오프, 영구 실패 분기)
- ADR-010: `AiScoreProvider` 의 ES dense_vector kNN 어댑터 채택
- ADR-011: WebSocket vs Server-Sent Events 알림 채널 (`suspicious_pattern_alerts` push)
- ADR-012: Read replica 도입 (transfer DB 읽기 부하 분산)
- ADR-013: Kafka 멀티 브로커 + RF 3 운영 토폴로지
