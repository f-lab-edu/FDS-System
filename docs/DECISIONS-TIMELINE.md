# 의사결정 Timeline — 무엇을 언제 왜 했고 무엇을 버렸는가

> 이 문서는 커밋 해시별로 의사결정의 시점·계기·대안 비교·버린 옵션을 기록한다.
> "왜 처음부터 이렇게 안 했나"는 모든 시스템의 정직한 질문이고, 이 timeline 은 그 답이다.

각 항목은 다음 형식이다.

```
[CommitHash] YYYY-MM 짧은 제목
- 직전 상태:  무엇이 문제였나
- 결정:       무엇으로 바꿨나
- 대안:       검토했지만 버린 것
- 비용:       이 결정이 남긴 부채
```

---

## Stage 0 — 시드 / 도메인 구축

### `4e019d7` 송금 도메인 infra 계층 구현 완료

- **직전 상태**: 새 프로젝트. 단일 모듈로 시작 가능했지만 처음부터 멀티 모듈 선택.
- **결정**: `services/transfer/{domain, application, infra, instances/api}` 4분할.
- **대안**:
  - **단일 모듈** — 초기 속도 빠름. 코드량 작아 분리 비용 큼.
  - **3계층(controller/service/repository)** — 익숙. 도메인 격리 약함.
- **버린 이유**: 처음부터 FDS 도메인 분리를 염두에 둠. 도메인 경계 흐려진 뒤 분리하는 비용이 훨씬 크다고 판단.
- **비용**: 모듈 5개부터 시작해 빌드 캐시/IDE 인덱싱 부담. 작은 변경에도 다수 모듈 컴파일.

### `25cb919` Snowflake ID infra 이전, CustomEnumType, Transaction 리팩터링

- **직전 상태**: ID 생성 책임이 도메인에 섞임. JPA enum 타입 매핑이 일관성 없음.
- **결정**: Snowflake → `common-domain`, JPA enum 은 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 통일.
- **대안**: UUID v7 (시간순) — Snowflake 대비 분산 환경에서 동기화 부담 적음.
- **버린 이유**: Snowflake 가 한국 핀테크 도메인 관행이고 64bit `BIGINT` PK 가 인덱스 효율 좋음.
- **비용**: `SNOWFLAKE_NODE_ID` 환경 변수 관리 필요. 멀티 인스턴스에서 충돌 시 ID duplicate 위험(현재 검증 부재 — LIMITATIONS 참조).

---

## Stage 1 — 멀티 모듈과 공통화

### `477c906` 송금 증액/차감, commonModule 통합

- **직전 상태**: HTTP 응답 객체와 Spring 의존성이 도메인에 섞이는 조짐.
- **결정**: `common-application` (HTTP 응답 공통) / `common-domain` (Spring 미의존) 분리.
- **대안**: 모든 공통 코드를 `common` 단일 모듈에.
- **버린 이유**: 도메인이 Spring 을 모르게 하는 헥사고날 원칙. 단일 `common` 은 결국 깨진다(검증된 패턴).
- **비용**: 의존성 경계 헷갈림. 신규 코드 작성 시 "어디 둬야 하나" 매번 판단 필요.

### `c828495` 멀티모듈 setup 머지

- **결정**: `services/transfer` 와 `services/fds` 부모 디렉터리 도입. service 경계가 디렉터리로 표현됨.
- **대안**:
  - `transfer-*` / `fds-*` flat 모듈 이름.
  - Gradle composite build 로 fds 를 별도 레포 사전 분리.
- **버린 이유**:
  - flat — 모듈 수 늘어나면 가독성 폭락.
  - 별도 레포 — 초기 단계엔 도메인 협업 비용이 너무 큼. 모놀리식 → MSA 이관은 나중에.
- **비용**: settings.gradle.kts 의 `include` 경로가 길어짐. IDE 의 reverse navigation 가끔 헷갈림.

---

## Stage 2 — Outbox 패턴 도입

### `186e1ef` 송금 이벤트 outbox 적용

- **직전 상태**: `@Transactional` 안에서 Kafka producer 직접 호출. 커밋 후 발행 실패하면 이벤트 유실.
- **결정**: `transfer_events` 테이블 + `@TransactionalEventListener(phase=AFTER_COMMIT)` 발행 + 실패 시 replay 스케줄러.
- **대안**:
  - **2PC** — XA 트랜잭션. PostgreSQL/Kafka 2PC 미지원.
  - **Debezium CDC** — `transactions` 테이블 변경을 Kafka 로 직접. 설정 복잡, 운영 부담.
  - **`AFTER_COMMIT` 발행만** (outbox 없음) — 프로세스 죽으면 유실.
- **버린 이유**:
  - 2PC — 인프라 부재.
  - Debezium — 한 사람이 도입할 부담 큼. 후속 마일스톤.
  - `AFTER_COMMIT` only — 신뢰성 부족.
- **비용**: `transfer_events` 테이블 폭증. 정기 정리 필요(미구현 — LIMITATIONS).

### `1be7051` 도메인 이벤트 기반 outbox 적용 phase2

- **결정**: Spring `ApplicationEventPublisher` 사용. 도메인 이벤트 → outbox 저장 → AFTER_COMMIT 후 Kafka.
- **대안**: 직접 outbox 테이블에 write (이벤트 추상화 없이).
- **비용**: 도메인 이벤트 모델 1세트 추가. `TransferCompleted` (Avro) ≠ `TransferCompleteEvent` (FDS 도메인) — 명명 충돌 잔존.

---

## Stage 3 — 분산 트레이싱과 빌드 인프라

### `6a42896` API 응답에 traceId 추가

- **결정**: HTTP 응답 body 에 traceId 동봉. 클라이언트가 신고할 때 traceId 만 보내면 됨.
- **대안**: 응답 헤더(`X-Trace-Id`) 만 — 클라이언트 코드가 매번 헤더 파싱.
- **비용**: 응답 스키마에 운영용 필드 노출. 외부 API 라면 거부 사유.

### `740c071` buildSrc 제거, build-logic 도입

- **직전 상태**: `buildSrc` 가 모든 모듈을 invalidate 시키는 동작. 작은 변경에도 풀빌드.
- **결정**: `build-logic` 별도 included build + convention plugin. 변경 격리.
- **대안**:
  - **`buildSrc` 유지** — 친숙.
  - **모든 모듈에 boilerplate 복붙** — 동기화 지옥.
- **버린 이유**: Gradle 공식 권장(8.x 부터). 블로그 글로 정리(`docs/etc/[blog] buildSrc 를 걷어내고 build-logic 을 도입해보자.md`).
- **비용**: 초기 학습. convention plugin 디버깅 까다로움.

### `9590def` 공통 traceId/spanId 변경, common 네이밍 변경

- **결정**: `delivery-http` → `common-application`, `shared-common` → `common-domain`. 분리 이유가 이름에 드러나게.
- **비용**: import 경로 대규모 변경 1회.

---

## Stage 4 — Kafka 모듈 + FDS 연결

### `8abafe8` kafka module 셋팅 (Avro)

- **결정**: kafka producer/consumer/config/model 을 `infrastructure/kafka/*` 별도 모듈로. Avro 직렬화 + Schema Registry.
- **대안**:
  - **JSON 직렬화** — 스키마 진화 어려움.
  - **Protobuf** — Avro 대비 Schema Registry 통합 약함(2025년 시점).
- **버린 이유**: Avro 가 Confluent Schema Registry 의 first-class. 컨슈머의 forward compatibility 검증 용이.
- **비용**: Avro 코드 생성 단계 필요. 빌드 시간 추가.

### `90b9682` 송금 성공 시 동기 Kafka send, 실패만 relay 처리

- **직전 상태**: 모든 이벤트를 relay 가 처리. 정상 케이스의 지연 추가.
- **결정**: 정상 케이스는 `AFTER_COMMIT` 직후 동기 send 시도. 실패만 outbox 에 남겨 relay 가 처리.
- **대안**: 100% relay 위임 — 정상 케이스도 일관성 있지만 지연.
- **버린 이유**: P99 지연 vs 코드 복잡도 트레이드오프에서 P99 선택.
- **비용**: 두 발행 경로(직접/Outbox)의 정합성 책임이 늘어남. 정상 발행 후 응답 직전에 프로세스 죽으면 outbox 에 안 들어감 — 미해결 갭(LIMITATIONS).

### `98ba2cf` FDS consumer app 추가 + transfer 디렉터리 정리

- **결정**: FDS 의 `application/port` 디렉터리를 `provided/required` 로 변경.
- **이유**: in/out 보다 "제공/필요" 가 인텐트 명확. 헥사고날 원전 용어와 다르지만 한국어 코드 리뷰에서 의도 전달.
- **비용**: 표준 헥사고날 문헌과 명명 불일치. 신규 합류자 학습 비용.

---

## Stage 5 — Relay 멀티 인스턴스와 파티셔닝

### `9bf9af6` transfer-relay 단일 스레드 → multiThread

- **직전 상태**: 단일 스레드 단일 인스턴스 — Outbox lag 누적.
- **결정**: 단일 인스턴스 멀티 스레드 (worker pool).
- **대안**: 멀티 인스턴스(처음부터) — 락 경합 모름.
- **버린 이유**: 단순함 우선. 한 인스턴스로 한계 측정 후 결정.
- **비용**: GIL 같은 한계는 없지만 단일 머신 자원에 묶임.

### `82eedde` 멀티 스레드 방식 1차

- **결정**: ThreadPool 도입.
- **비용**: 스레드 수 튜닝 근거 미수립.

### `1ba6a6c` Spring Batch 로 이관

- **직전 상태**: 직접 스케줄러로 batch 처리. 트랜잭션/재시도/체크포인트 직접 관리.
- **결정**: Spring Batch 의 step/chunk 모델 채택.
- **대안**:
  - **`@Scheduled` 유지** — 가볍지만 재시작 시 진행 상태 잃음.
  - **외부 워커(Sidekiq 류)** — 인프라 추가.
- **버린 이유**: `@Scheduled` — Job 메타데이터 손실. 외부 워커 — JVM 일관성 깨짐.
- **비용**: Spring Batch 의 메타데이터 테이블 추가(`BATCH_*`). flyway baseline 충돌이 한때 문제(README 참고).

### `e6b1744` 낙관 락 → 비관 락 회귀 + 부하 테스트

- **직전 상태**: 계좌 잔액 갱신에 `@Version` 낙관 락. 부하 시 retry 폭증.
- **결정**: `findByAccountNumberWithLock` 으로 비관 락 SELECT FOR UPDATE.
- **대안**:
  - **낙관 락 유지 + 재시도 횟수 증가** — race 가 심해질수록 retry storm.
  - **분산 락(Redis Redlock)** — DB 락이 충분한 시점에 과한 인프라.
- **버린 이유**: 실패율 측정값(80% → 18%)이 비관 락 손을 들어줌. 같은 머신/DB 안에서 일어나는 락이라 DB 락이 정답.
- **비용**: 정렬된 락 획득 순서 강제(`loadUsers` 의 sender < receiver). 데드락 회피 책임이 코드에 노출.

---

## Stage 6 — 부하 테스트와 TIME_WAIT

### `8cf1ad7` RestTemplate TIME_WAIT 발견

- **직전 상태**: k6 의 부하 테스트에서 socket exhaustion. `netstat | grep TIME_WAIT` 가 수만 단위.
- **결정**: Apache HttpClient 5 + Connection Pool 사용으로 keepalive 적용.
- **대안**:
  - **`net.ipv4.tcp_tw_reuse`** sysctl — OS 의존, 컨테이너에선 권한 부족.
  - **WebClient(Reactor Netty)** — 비동기 모델 전체 도입 필요.
- **버린 이유**:
  - sysctl — 운영 자율도 낮음.
  - WebClient — 부하 테스트 코드만 바꾸려는 단계에선 과함.
- **비용**: ClientHttpRequestFactory 설정 추가. PoolingHttpClientConnectionManager 의 max 설정이 부하 시나리오별 별도 튜닝 필요.

---

## Stage 7 — FDS 분석 로직과 Kafka Streams

### `93351ef` FDS 이상탐지 분석 비즈니스 코드 추가

- **결정**: `AnalyzeTransferService` + Rule Engine (HIGH_AMOUNT, SINGLE_HIGH_AMOUNT, RAPID_TRANSFER).
- **대안**:
  - **외부 룰 엔진 (Drools)** — 학습 부담.
  - **DSL** — 자체 구문 설계 비용.
- **버린 이유**: 룰 3개 수준에선 Kotlin `when` 분기가 더 가독성 좋음.
- **비용**: 룰 N개 넘어가면 코드가 갈수록 두꺼워질 위험. Drools 검토는 후속 작업.

### `6f9be73` FDS fraud 적용 머지

- **결정**: Kafka Streams 의 `processTransferEvents` (stateless) + `detectSuspiciousPatterns` (10분 windowedBy).
- **대안**:
  - **Flink** — 운영 부담.
  - **자체 Redis sliding window** — Streams 의 State Store 보다 복잡한 로직 자체 구현.
- **버린 이유**: 이미 Kafka 가 first-class. Streams 가 토픽 ↔ 토픽 변환의 권장 도구.
- **비용**: RocksDB 로 State Store. 디스크/운영 비용은 LIMITATIONS 참조.

---

## Stage 8 — 분산 트레이싱 컨텍스트 전파

### `3b37ee3` ContextPropagatingTaskDecorator + TracingTransformer

- **직전 상태**: traceId 가 `@Async` 스레드에서 끊김. Kafka 컨슈머에서 새 trace 시작.
- **결정**:
  - `AsyncConfig.taskDecorator = ContextPropagatingTaskDecorator()`
  - Kafka Streams: `TracingTransformer` 가 header 의 `traceparent` 추출 → MDC 주입.
- **대안**:
  - **OpenTelemetry SDK 직접 사용** — Spring 통합이 Micrometer 보다 약함(2024년 시점).
  - **수동 MDC put/remove** — 누락 위험.
- **버린 이유**: Micrometer Observation 이 Spring Boot 3 의 표준. SDK 직접 다루기는 ROI 낮음.
- **비용**: traceId 가 Kafka 헤더로 흐르는 만큼 페이로드 size 증가(~50 byte/이벤트).

---

## Stage 9 — ELK 통합

### `d463d3a` ELK Stack 통합

- **결정**: logback `LogstashEncoder` + Filebeat → ES + Kibana.
- **대안**:
  - **Loki + Grafana** — Prometheus 와 친화적. 풀 텍스트 검색 약함.
  - **CloudWatch Logs** — 클라우드 종속.
- **버린 이유**: 로컬 Docker 친화. 풀 텍스트 검색이 운영 디버깅에서 결정적.
- **비용**: ES 운영비. 디스크/RAM 부담.

### (이 PR) AccessLog 분리 + Filebeat 인덱스 분기

- **직전 상태**: app 로그만 적재. HTTP access 로그가 없어 latency/status 분석 불가.
- **결정**: Tomcat AccessLog Valve(JSON pattern) + Filebeat input 2 분리(`app-logs-*` / `access-logs-*`).
- **대안**: 단일 인덱스 + `log_type` 필드 필터 — 인덱스 매핑 explosion 위험.
- **비용**: 인덱스 2개 생성/관리. ILM 미설정으로 디스크 폭발 위험(LIMITATIONS).

---

## Stage 10 — Redis 일일 한도 (이 PR)

### Redis 인프라 + Daily Limit Cache

- **직전 상태**: `TransferValidator.validateSender` 의 일일 한도 검증이 1개월간 주석 처리. DB 집계 쿼리 부담을 회피하려고 비활성화된 채 방치.
- **결정**:
  - Redis 컨테이너 신설(`docker-compose.yml`).
  - `DailyTransferAmountCachePort` (application) + `RedisDailyTransferAmountCacheAdapter` (infra).
  - 키: `daily:transfer:{userId}:{yyyyMMdd}` (KST 기준). TTL 26h.
  - `User.validateTransferAmount(amount, dailyAccumulated)` 시그니처 확장.
  - `TransferValidator` 가 누적치를 받는 형태로 변경. 일일 한도 활성화.
- **대안**:
  - **DB `SUM(amount) WHERE created_at >= today_start`** — 매 송금마다 풀 스캔.
  - **로컬 캐시(Caffeine)** — 멀티 인스턴스 일관성 깨짐.
  - **CRDT 기반 분산 카운터** — 과한 인프라.
- **버린 이유**:
  - DB sum — 송금 빈도 높은 사용자에 hot row.
  - Caffeine — 검증 결과가 인스턴스별로 갈림.
- **비용**: 캐시 미스 시 0 으로 시작 → 초기 부정확 (단, 보수적 안전). 트랜잭션 롤백 시 캐시 over-estimate 잔존(LIMITATIONS 참조).

---

## Stage 11 — FDS 스트림 완성 (이 PR)

### `detectSuspiciousPatterns` 활성화 + `SuspiciousPatternAlertConsumer`

- **직전 상태**: `application.yml` 에서 `definition: processTransferEvents` 만 활성. 10분 윈도우 집계 코드는 있지만 실제로 안 돌고 있었음.
- **결정**:
  - `definition: processTransferEvents;detectSuspiciousPatterns` 로 동시 활성화.
  - `suspicious-patterns` 토픽 별도 `@KafkaListener` 가 `suspicious_pattern_alerts` 테이블에 영속화.
- **대안**:
  - Streams 안에서 직접 JPA 호출 — Streams 스레드 블록 위험.
  - 별도 마이크로서비스 — 과한 분리.
- **버린 이유**: Streams 처리부와 영속화부의 책임 분리.
- **비용**: spring-kafka 의존성 추가. 두 종류의 컨슈머(Streams + 일반)가 한 앱에 공존.

---

## 메타 — 이 timeline 의 사용법

1. **PR 리뷰**: "왜 이렇게 했나" 가 나오면 해당 stage 인용.
2. **신규 합류자 온보딩**: 첫 주에 이 문서를 읽힘. 시스템의 결정 무게를 이해.
3. **회고**: phase 별 회고 글이 이 timeline 의 stage 들을 묶어 평가.
4. **면접**: "버린 옵션" 컬럼이 가장 자주 묻는 부분.

후속 PR 이 들어오면 이 문서의 **새 stage 를 추가**한다. 결정의 시간 축은 시스템의 진짜 설계 문서다.
