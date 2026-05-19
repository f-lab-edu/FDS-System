# LIMITATIONS — 이 프로젝트의 솔직한 한계

> 이 문서는 면접관이 "약점이 뭐냐"고 물었을 때 즉답할 수 있도록 정리한다.
> 자랑이 아니라 다음 단계의 작업 목록이며, 빅테크 운영 수준과의 갭을 시인하는 목록이다.

본 문서는 두 가지를 분리한다.

1. **알면서 안 한 것** — 의도적 미구현. 후속 PR/마일스톤으로 가는 명시적 갭.
2. **운영 검증 부재** — 코드로는 풀었지만 실제 멀티 인스턴스 / 장기 운영 경험이 없는 영역.

---

## 1. 알면서 안 한 것 (Known Gaps)

### 1.1 NoOp 어댑터 잔존

| 위치 | 어댑터 | 상태 |
|---|---|---|
| `services/fds/instances/api/.../adapter/NoOpAiScoreProvider.kt` | `AiScoreProvider` | ML 미구현 → 항상 `null` |

`AiScoreProvider` 는 ES dense_vector + kNN 으로 대체할 확장점만 만들어 둔 상태다 ([PoC 설계](etc/ml-anomaly-detection-poc.md)).
**RAPID_TRANSFER 의 read-model 어댑터(`JpaRecentTransferCountQueryAdapter`)는 실제 구현으로 교체 완료**.

### 1.2 단일 인프라 노드

- **PostgreSQL** — 단일 노드. Read replica / 파티셔닝 / sharding 없음.
- **Kafka** — 단일 브로커 (`replication-factor: 1`). 운영에선 최소 3 브로커 + RF 3.
- **Redis** — 단일 노드. Sentinel/Cluster 미구성.
- **Elasticsearch** — 단일 노드. ILM(Index Lifecycle Management) 비활성 (`setup.ilm.enabled: false`). 장기 운영 시 디스크 폭발 가능.

### 1.3 DLQ / 알림 채널

- README 에 "DLQ Worker + TTL" 적었지만 **dlq_events 테이블만 있고 worker 없음**.
- `suspicious_pattern_alerts` 적재까지는 구현, **WebSocket/Slack/이메일 푸시 어댑터 0개**.

### 1.4 인증·인가

- `admin_users` / `users` 테이블만 있고 **JWT/세션 인증 미구현**.
- API 호출자 식별이 안 됨 → 부하 테스트가 단일 사용자 시뮬레이션에 묶여 있다.

### 1.5 보안

- 비밀번호 평문 저장 (`pass1234`) — 데모 환경.
- Secret rotation 부재, Vault/AWS Secrets Manager 미연동.
- Kafka SASL/SSL 미설정.

---

## 2. 운영 검증 부재 (Unproven in Production)

### 2.1 Relay 멀티 인스턴스의 실제 운영

`docs/etc/performance-test.md` 의 470→1410 TPS, 30/30/20→33.3/33.3/33.3 분포 수치는 **로컬 단일 머신의 단발성 측정**이다. 실제로 다음 시나리오는 미검증.

- 인스턴스 1대 추가 시점에 leader election / partition rebalancing 어떻게 처리되나
- 한 인스턴스가 KILL -9 되었을 때 STUCK 이벤트 복구 시간
- `instance_id` 와 `partition_count` 가 불일치할 때 (스케일링 도중) 발생하는 동작
- DB connection pool 고갈 시 SKIP LOCKED 의 latency profile

### 2.2 Kafka Streams 운영

- **State Store(RocksDB) 운영 경험 없음**. 디스크 용량 / 백업 / 리스토어 미검증.
- `num.stream.threads: 2` 가 적절한지 부하 테스트로 증명 안 됨.
- 토픽 재처리(seek to beginning) 시 윈도우 결과의 멱등성 미검증.
- Streams 와 일반 `@KafkaListener` 의 commit offset 정책이 다른데, **장애 시 정합성 비교 안 함**.

### 2.3 Redis 일일 한도의 동시성

- `INCRBY` 는 원자적이지만, **트랜잭션 커밋 전에 `addTodayAmount` 호출**한다 (`TransactionService.kt`).
  - 트랜잭션 롤백 시 캐시는 over-estimate 상태로 남음 (보수적 안전, but 정확도 손실).
  - 운영에선 outbox 패턴처럼 트랜잭션 커밋 후 hook 으로 호출해야 함. 미구현.
- TTL 만료 직전 자정 경계에서 race condition 가능 (마지막 호출의 TTL 이 짧게 잡힘).

### 2.4 분산 트레이싱 sampling

- `probability: 1.0` — 100% 샘플링. 운영 부하에서 추적 데이터 폭증.
- 운영 환경의 동적 샘플링(예: 에러는 100%, 정상은 10%) 미구성.

### 2.5 ELK 적재 지연

- `app-logs-*` / `access-logs-*` 인덱스 분리는 했지만 **Filebeat → ES 적재 latency 측정 0회**.
- Logstash 없이 Filebeat 단독 처리 — 복잡한 grok parsing 이 필요한 시점에 병목.
- Index 매핑 사전 정의 안 함 → 동적 매핑으로 인한 field explosion 위험.

---

## 3. 테스트 커버리지 갭

### 3.1 통합 테스트 (Q1 작업 후 상태)

신규 추가:

| 영역 | 테스트 | 상태 |
|---|---|---|
| Redis 일일 한도 어댑터 | `RedisDailyTransferAmountCacheAdapterIntegrationTest` | Testcontainers Redis |
| RAPID_TRANSFER read-model | `JpaRecentTransferCountQueryAdapterIntegrationTest` | Testcontainers PostgreSQL |

### 3.2 여전히 부재

- **`TransferEventConsumer` Kafka Streams end-to-end** — Avro 직렬화 + Schema Registry + FDS 분석 + 결과 토픽까지의 통합 테스트 없음. 운영 환경에서만 검증되는 위험.
- **`TransactionService` end-to-end** — Outbox 발행 + Kafka 수신까지 단일 테스트 없음.
- **`SuspiciousPatternAlertConsumer`** — @KafkaListener 통합 테스트 없음.
- **`AnalyzeTransferService` 룰 분기** — 단위 테스트 부재 (룰 추가 시 회귀 위험).
- **부하 테스트 자동화** — k6 시나리오는 있지만 CI 에서 임계치 기반 fail 게이트 없음.

### 3.3 정량적 메트릭 부재

- 통합 테스트 coverage 측정 미수행 (JaCoCo 플러그인은 있지만 보고서 검증 안 함).
- Mutation testing(Pitest) 미도입.

---

## 4. 코드 품질 갭

### 4.1 Lint / Static Analysis

- `ktlint`/`detekt` 미적용. 컨벤션 일관성 검증 없음.
- SonarQube 도큐먼트는 있지만 (`docs/etc/.../sornarQube_연동.md`) 실제 CI 연동 미확인.

### 4.2 의존성 관리

- BOM 사용 중이지만 `dependabot.yml` / `renovate.json` 없음. 보안 패치 자동화 부재.

### 4.3 라이브러리 취약점 점검

- 문서 (`라이브러리_취약점_점검.md`) 만 있고 OWASP Dependency-Check 같은 자동 게이트 없음.

---

## 5. 설계 단계 인정

### 5.1 헥사고날 절충

`docs/adr/ADR-001-hexagonal-architecture-pragmatic-adoption.md` 에 적었듯, **순수 헥사고날이 아니다**.

- `services/fds/infra/.../TransferEventConsumer.kt` 는 Avro 모델(`TransferEventAvroModel`)을 직접 import. 인프라 모델이 어댑터 안에 노출되어 있음.
- `application/required` 에 `RecentTransferCountQueryPort` 같은 read-model 포트가 추가되면서 CQRS 색채가 섞임 — 명시적 결정 없이 자라남.
- domain 모듈이 의존하는 `common-domain` 의 `Snowflake`/`Money` 는 도메인 원칙상 `common` 으로 빼지 않는 게 정석. **편의 우선의 결정**.

### 5.2 모듈 분리 시점의 즉흥성

- 처음부터 multi-module 로 시작. 작은 코드량에 비해 모듈 수가 많아 빌드 캐시 압박이 큰 편 (`./gradlew build` 가 모듈 의존 해석에 시간 소비).
- "왜 fds 와 transfer 를 별도 service 폴더로 두었나" 는 ADR 에 적었지만, **모듈 단위 의존성 그래프 검증(예: shrinkwrap 테스트)은 안 함**.

### 5.3 도메인 이벤트 일관성

- `TransferCompleted` (Avro), `TransferCompleteEvent` (FDS 도메인), `TransferAnalysisResult` (FDS 출력) 등 **유사 이름의 이벤트 모델이 분산**. 명명 컨벤션 통일 필요.
- `RiskLog` 와 `FraudDetectionJpaEntity` 의 필드 매핑이 어댑터 안에 하드코딩 — 매퍼 분리 필요.

---

## 6. 우선순위가 매겨진 다음 작업

| Priority | 작업 | 효과 |
|---|---|---|
| P0 | DLQ Worker 구현 + 재처리 정책 | 데이터 유실 방어 |
| P0 | Kafka 멀티 브로커 + RF 3 docker-compose | 가용성 |
| P1 | `TransactionService` end-to-end 통합 테스트 (Testcontainers Kafka + PostgreSQL) | 회귀 방지 |
| P1 | WebSocket/Slack 알림 어댑터 (`suspicious_pattern_alerts` → push) | 운영 즉시성 |
| P1 | `addTodayAmount` 트랜잭션 커밋 후 호출로 변경 (Outbox 패턴 또는 `@TransactionalEventListener(phase=AFTER_COMMIT)`) | 정확도 |
| P2 | ML 어댑터 (`AiScoreProvider` 실 구현) | 룰 한계 보완 |
| P2 | Read replica + 일부 조회 라우팅 | DB 부하 분산 |
| P2 | ILM 활성화 + Hot/Warm/Cold 인덱스 | ES 운영비 |
| P3 | ktlint/detekt + GitHub Actions CI 게이트 | 품질 자동화 |
| P3 | Dependabot + OWASP Dependency-Check | 보안 |

---

## 7. 면접용 정답지

> "이 프로젝트의 가장 큰 약점은?"

**1순위**: 운영 검증 부재. 로컬 단발 측정으로 'TPS 1,410' 같은 숫자가 나왔지만, **실제 멀티 인스턴스에서 한 노드가 죽었을 때의 복구 시간**, **Kafka 브로커 한 대 장애 시 Producer 동작**, **State Store 디스크 풀 상황** 을 직접 못 겪어봤다. 결과 코드는 작동하지만 **실패 시나리오에서의 동작은 가설이다**.

**2순위**: 테스트 피라미드 위쪽이 비어 있다. 단위 테스트 + 일부 통합 테스트는 있지만 contract test, mutation test, chaos test 가 없다. 회귀 검출은 사람 눈에 의존.

**3순위**: Outbox 의 정확성을 코드는 보장하지만 **운영 게이트가 없다**. monitoring 디렉터리에 prometheus 설정만 있고, "outbox lag > 5초 이면 알림" 같은 alert rule 이 빠져 있다.

---

## 8. 메타 인지

이 LIMITATIONS 문서 자체가 후속 PR 의 입력이 되어야 한다. 항목이 해소되면 표에서 줄을 긋고, 새로 발견된 갭은 추가. 정직한 한계 인정이 빅테크 시니어 레벨의 출발점이다.
