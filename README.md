# Transentia

> 트랜잭션을 넘어, 자금의 흐름을 인식하고 판단하는 시스템.

송금 도메인에서 정합성·실시간성·운영성 세 축을 단계적으로 풀어간 이상거래탐지 시스템.

- 정합성: 송금 트랜잭션과 Kafka 발행을 Outbox 로 묶는다. 둘 중 하나만 성공하는 상태가 없다.
- 실시간성: 룰 기반 단일 거래 판정 + Kafka Streams 10분 윈도우 집계. 송금이 처리되는 동안 판정한다.
- 운영성: traceId 한 줄로 송금 → Kafka → FDS → DB 를 따라간다.

각 결정의 근거는 ADR, 실측은 실험 디렉터리, 회고는 phase 별 문서로 남겼다.


## 시스템 한 장 요약

<p align="center">
  <img src="docs/diagrams/svg/02-data-flow.svg" alt="데이터 흐름 다이어그램" width="900"/>
</p>

| 다이어그램 | SVG | 소스 |
|---|---|---|
| System Context | [01-system-context.svg](docs/diagrams/svg/01-system-context.svg) | [.mmd](docs/diagrams/src/01-system-context.mmd) |
| 데이터 흐름 | [02-data-flow.svg](docs/diagrams/svg/02-data-flow.svg) | [.mmd](docs/diagrams/src/02-data-flow.mmd) |
| 헥사고날 계층 | [03-hexagonal-layers.svg](docs/diagrams/svg/03-hexagonal-layers.svg) | [.mmd](docs/diagrams/src/03-hexagonal-layers.mmd) |
| 분산 트레이싱 시퀀스 | [04-distributed-tracing.svg](docs/diagrams/svg/04-distributed-tracing.svg) | [.mmd](docs/diagrams/src/04-distributed-tracing.mmd) |
| Outbox 파티셔닝 Before/After | [05-outbox-partitioning.svg](docs/diagrams/svg/05-outbox-partitioning.svg) | [.mmd](docs/diagrams/src/05-outbox-partitioning.mmd) |

자세한 컨테이너 단위 다이어그램과 다이어그램별 설명은 [`docs/etc/system-architecture.md`](docs/etc/system-architecture.md).

---

## Phase 1 — Outbox + 파티셔닝 (정합성)

<p align="center">
  <img src="docs/diagrams/svg/05-outbox-partitioning.svg" alt="Outbox 파티셔닝 Before/After" width="800"/>
</p>

**문제**: `@Transactional` 안에서 Kafka 를 직접 호출하면 커밋 후 발행 실패 시 이벤트가 유실된다. 2PC 는 인프라 부담이 크다.

**결정**: 트랜잭션 안에서 `transfer_events` 테이블에 outbox row 만 적재 → 별도 Relay 가 `FOR UPDATE SKIP LOCKED` 로 폴링.

**진화 경로**:

1. 단일 Relay → **다대 Relay 도입 후 빈 배치 1,658회** 발생
2. `MOD(event_id, n) = instanceId` 파티셔닝 도입 → **DB 쿼리 99% 감소** (1,673회 → 17회)
3. Spring Batch 로 이관해 single-instance multi-thread 전략 채택 (commit `1ba6a6c`)

| 지표 | Before | After | 개선 |
|---|---|---|---|
| DB 쿼리 수 | 1,673회 | 17회 | **99% 감소** |
| 분배 | 30/30/20 | 33.3/33.3/33.3 | 균등 |
| 락 경합 | 높음 | 0 | 제거 |
| TPS | 470 | 1,410 | **3배** |

상세: [`docs/etc/performance-test.md`](docs/etc/performance-test.md) · [`docs/etc/partitioning-strategy.md`](docs/etc/partitioning-strategy.md) · ADR [001](docs/adr/ADR-001-hexagonal-architecture-pragmatic-adoption.md) [002](docs/adr/ADR-002-transactional-outbox-pattern.md) [003](docs/adr/ADR-003-relay-partitioning-by-modulo.md) · 회고 [Phase 1](docs/retrospective/phase-1-outbox-and-partitioning.md)

---

## Phase 2 — 관측성과 캐시 (운영성)

**문제 1 (트레이싱 끊김)**: `@Async` 스레드와 Kafka producer/consumer 경계에서 traceId 가 사라져 장애를 추적할 수 없었다.

**결정**: Micrometer Observation + `ContextPropagatingTaskDecorator` 로 `@Async` 컨텍스트 전파, Kafka Streams `TracingTransformer` 로 헤더에서 traceId 추출.

**문제 2 (로그 검색 불가)**: 컨테이너 stdout 만 보면 traceId 로 cross-service 검색이 안 된다.

**결정**: logback `LogstashEncoder` 로 JSON 파일 출력 → Filebeat 로 Elasticsearch 적재. 두 종류 인덱스 분리:
- `app-logs-*` : 애플리케이션 로그 (`traceId` MDC 포함)
- `access-logs-*` : Tomcat AccessLog Valve (HTTP 요청/응답)

**문제 3 (일일 한도 비활성화)**: `TransferValidator` 의 일일 한도 검증이 `// TODO: redis cache 를 사용해야 할까?` 주석 처리된 채 1개월 방치.

**결정**: Redis `INCRBY` + `EXPIRE` 로 `daily:transfer:{userId}:{yyyyMMdd}` 키 관리. 도메인은 누적치를 파라미터로 받는 포트 패턴.

| 지표 | 효과 |
|---|---|
| traceId 일관성 | Transfer → Kafka → FDS 끝까지 동일 traceId 유지 |
| AccessLog | Kibana 에서 `method/uri/status/duration_ms` 필드별 검색 |
| Redis 일일 한도 | DB 집계 쿼리 제거, O(1) 캐시 |

상세: [`docs/etc/observability-setup - ELK_Stack.md`](docs/etc/observability-setup%20-%20ELK_Stack.md) · [`docs/etc/W3C Trace Context.md`](docs/etc/W3C%20Trace%20Context.md) · ADR [004](docs/adr/ADR-005-redis-daily-limit-cache.md) [006](docs/adr/ADR-006-elk-observability.md) [007](docs/adr/ADR-007-distributed-tracing-context-propagation.md) · 회고 [Phase 2](docs/retrospective/phase-2-observability-and-cache.md)

---

## Phase 3 — 스트림 처리와 FDS (실시간성)

**문제**: 룰 기반 단일 거래 탐지는 빠르지만 "10분간 5건 송금" 같은 시간 패턴은 못 잡는다. 외부 ML 서비스 호출은 응답성을 깬다.

**결정**: Kafka Streams 듀얼 파이프라인 + ML 확장점.

```
transfer-transaction-events
    ├─ processTransferEvents  (stateless, 즉시 차단/리뷰 판정)
    │      └→ fds-analysis-results
    │      └→ fraud_detections 테이블 적재
    │
    └─ detectSuspiciousPatterns (stateful, 10분 windowedBy)
           └→ suspicious-patterns 토픽
                └→ @KafkaListener → suspicious_pattern_alerts
```

**룰 엔진** (`AnalyzeTransferService`):
- `SINGLE_HIGH_AMOUNT` — 2,000만원 단일 거래
- `HIGH_AMOUNT` — 임계치 초과
- `RAPID_TRANSFER` — N분 내 X건 (전용 Read-Model 쿼리)

**ML 확장점**: `AiScoreProvider` 포트만 두고 `NoOpAiScoreProvider` 어댑터로 시작. 후속 PR 에서 ES dense_vector kNN 어댑터로 교체. 설계: [`docs/etc/ml-anomaly-detection-poc.md`](docs/etc/ml-anomaly-detection-poc.md).

상세: [`docs/etc/kafkaStream.md`](docs/etc/kafkaStream.md) · ADR [004](docs/adr/ADR-004-kafka-streams-for-pattern-detection.md) · 회고 [Phase 3](docs/retrospective/phase-3-streaming-and-fds.md)

---

## 아키텍처 원칙

| 원칙 | 적용 |
|---|---|
| **헥사고날 + 실용 절충** | `domain → application(ports) → infra(adapters) ← instances/api`. 순수성 100% 대신 80/20. ADR [001](docs/adr/ADR-001-hexagonal-architecture-pragmatic-adoption.md). |
| **이벤트 우선** | 송금/탐지/알림 모두 이벤트 모델로 추상화. Kafka 토픽이 시스템 경계. |
| **확장점은 포트로** | Redis/ML/AccessLog 모두 포트 + 어댑터로 끼워넣을 수 있는 자리만 만들어 둠. |
| **단계적 진화** | 한 번에 큰 시스템 그리지 않음. Phase 1 동작 후 Phase 2/3 결정. |
| **빌드는 컨벤션** | `build-logic/` 의 convention plugin 으로 모듈 boilerplate 제거. ADR [008](docs/adr/ADR-008-build-logic-convention-plugins.md). |

---

## 기술 스택

- **언어**: Kotlin (도메인/어댑터), Java 21 (런타임)
- **프레임워크**: Spring Boot 3.x, Spring Cloud Stream, Spring Kafka, Spring Batch
- **메시징**: Apache Kafka 3.9 + Schema Registry + Avro
- **저장소**: PostgreSQL 15, Redis 7
- **관측성**: Micrometer Observation, Prometheus, ELK 8.x (Elasticsearch + Kibana + Filebeat)
- **빌드**: Gradle 8.x + Kotlin DSL + Convention Plugins
- **테스트**: JUnit5 + Testcontainers + k6 (부하)
- **인프라**: Docker Compose

---

## 모듈 구조

```
back-end/
├── build-logic/                 # Gradle convention plugins
├── common/
│   ├── common-domain/           # 도메인 공통(Snowflake, Money, Amount, ...)
│   └── common-application/      # 애플리케이션 공통
├── infrastructure/
│   └── kafka/                   # producer/consumer/config/model
├── services/
│   ├── transfer/
│   │   ├── domain/              # Transaction, User, AccountBalance, Validator
│   │   ├── application/         # TransactionService, 포트 정의
│   │   ├── infra/               # JPA, Redis, Kafka producer 어댑터
│   │   └── instances/
│   │       ├── api/             # Transfer-API (:8080)
│   │       └── transfer-relay/  # Outbox Relay
│   └── fds/
│       ├── domain/              # RiskLog, FraudRule, TransferCompleteEvent
│       ├── application/         # AnalyzeTransferService, 포트 정의
│       ├── infra/               # Kafka Streams, JPA 어댑터
│       └── instances/api/       # FDS-API (:8082)
├── monitoring/                  # filebeat.yml, prometheus.yml
├── load-test/                   # k6 시나리오
└── docs/
    ├── adr/                     # Architecture Decision Records
    ├── retrospective/           # 단계별 회고
    ├── load-test/               # 부하 테스트 분석
    └── etc/                     # 기술 블로그/메모
```

---

## 실행

### 로컬 (개별 실행)

```bash
# 1. 인프라
docker compose up -d postgres kafka schema-registry init-topics redis elasticsearch kibana filebeat

# 2. Transfer API
./gradlew :services:transfer:instances:api:bootRun

# 3. FDS API
./gradlew :services:fds:instances:api:bootRun

# 4. Relay (선택)
./gradlew :services:transfer:instances:transfer-relay:bootRun
```

### 컨테이너 (전체)

```bash
./gradlew :services:transfer:instances:api:bootJar :services:fds:instances:api:bootJar
docker compose up -d
docker compose ps
```

### 부하 테스트

```bash
k6 run load-test/vus-100.js
```

자세한 시나리오는 [`docs/load-test/README.md`](docs/load-test/README.md).

---

## 데이터 모델

| 테이블 | 도메인 | 설명 |
|---|---|---|
| `users` / `account_balance` | Transfer | 사용자, 잔액 |
| `transactions` | Transfer | 송금 트랜잭션 본체. `idx_tx_sender_created` 로 RAPID_TRANSFER 룰 쿼리 인덱스 |
| `transfer_events` | Transfer (Outbox) | 이벤트 발행 큐. MOD 파티셔닝 키 |
| `fraud_rules` | FDS | 룰 정의 (JSONB threshold) |
| `fraud_detections` | FDS | 분석 결과 (RiskLog → JPA) |
| `suspicious_pattern_alerts` | FDS | 10분 윈도우 의심 패턴 알림 |
| `correction_log` / `dlq_events` | 운영 | 복구/실패 이력 |

전체 ERD: [`docs/ERD.puml`](docs/ERD.puml).

---

## 운영 권한

| Role | 권한 |
|---|---|
| `SUPER_ADMIN` | 전체 |
| `RULE_ADMIN` | 룰 CRUD |
| `AUDITOR` | 이력 조회 |
| `OPS_AGENT` | 장애 대응 |
| `RISK_ANALYST` | 탐지 분석 |
| `READ_ONLY` | 조회만 |

---

## 남은 작업

- `AiScoreProvider` 의 실 어댑터 (ES dense_vector kNN). 설계는 `docs/etc/ml-anomaly-detection-poc.md`.
- 의심 패턴 알림 push 채널 (WebSocket / Slack).
- DLQ Worker.
- Phase 3 전환 PoC. 트리거는 `outbox_oldest_pending_seconds` p99 > 30s. 자세한 단계는 `docs/EVOLUTION-ROADMAP.md`.

---

## 문서

- **아키텍처**: [`docs/etc/system-architecture.md`](docs/etc/system-architecture.md), [`docs/etc/현실적인 헥사고날 아키텍처와의 타협.md`](docs/etc/현실적인%20헥사고날%20아키텍처와의%20타협.md)
- **ADR**: [`docs/adr/README.md`](docs/adr/README.md)
- **회고**: [`docs/retrospective/README.md`](docs/retrospective/README.md)
- **부하 테스트**: [`docs/load-test/README.md`](docs/load-test/README.md)
- **기술 블로그/메모**: [`docs/etc/`](docs/etc/)

---

## 라이선스

MIT. PR/이슈 환영.
