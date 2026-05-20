# ADR-004: 패턴 탐지를 위한 Kafka Streams 채택

- **Status**: Accepted
- **Date**: 2026-02-20
- **Deciders**: Backend Lead
- **Tags**: streaming, fds, kafka-streams, stateful, windowing

---

## Context

FDS (Fraud Detection System) 의 탐지 로직은 두 가지로 나뉜다.

### 1. Stateless 단건 탐지

- 송금 한 건 단위로 판단할 수 있는 룰.
- 예: "송금 금액 > 5,000,000 원" / "수취 계좌가 블랙리스트" / "송금자/수취자 동일 계좌".
- 입력 1건 → 출력 1건.

### 2. Stateful 윈도우 집계 탐지

- 시간 윈도우 내 누적 통계가 필요한 룰.
- 예: **10분 안에 동일 수신 계좌로 5건 이상 송금이 들어오면 분산송금 의심**.
- 예: **10분 안에 동일 수신 계좌의 누적 입금액이 2,000만 원 초과 시 자금세탁 의심**.
- 입력 N건 → 출력 1건 (윈도우 닫힐 때).

(2) 는 단순 Kafka Consumer 로는 풀기 어렵다. 윈도우, 그룹화, 상태 저장이 필요하기 때문이다.

### 후보 기술

- Apache Flink
- Apache Spark Streaming
- Kafka Streams
- 자체 Redis sliding window 구현

각각의 장단점이 명확하고 운영 비용 차이가 크다.

---

## Decision

**Stateful 윈도우 집계 탐지에 Kafka Streams 를 채택한다. Stateless 단건 탐지는 일반 Kafka Consumer (Spring Kafka `@KafkaListener`) 로 처리한다.**

### 1. 토폴로지 구조

`services/fds/infra/.../FraudPatternStreamProcessor.kt:42`

```kotlin
@Bean
fun detectSuspiciousPatterns(): Function<KStream<String, TransferEventAvroModel>, KStream<String, String>> {
    return Function { input ->
        input
            .selectKey { _, event -> event.receiverId.toString() }   // 수신 계좌로 그룹화
            .groupByKey()
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(10)))
            .aggregate(
                { AccountTransferStats() },                          // 초기 상태
                { accountId, event, stats ->
                    stats.copy(
                        count = stats.count + 1,
                        totalAmount = stats.totalAmount + event.amount.toLong(),
                        lastEventId = event.eventId.toString(),
                        lastTimestamp = System.currentTimeMillis()
                    )
                },
                Materialized.with(Serdes.String(), AccountTransferStatsSerde(objectMapper))
            )
            .toStream()
            .selectKey { windowedKey, _ -> windowedKey.key() }
            .filter { _, stats ->
                stats.count >= 5 || stats.totalAmount >= 20_000_000L
            }
            .mapValues { accountId, stats -> /* SuspiciousPatternAlert JSON */ }
    }
}
```

### 2. 핵심 구성 요소

| 요소 | 역할 |
|---|---|
| `selectKey` | `receiverId` 로 키 재설정 → 동일 수신 계좌끼리 같은 파티션 |
| `groupByKey` | 키 기준 그룹화 |
| `windowedBy(TimeWindows.ofSizeWithNoGrace(10m))` | 10분 Tumbling window, grace period 없음 |
| `aggregate` | 윈도우 안에서 `AccountTransferStats` 누적 |
| `Materialized` | 상태를 RocksDB State Store 에 영속화 |
| `filter` | 임계치 초과만 통과 |

### 3. Stateless 탐지는 분리

블랙리스트 / 단건 금액 임계치 등은 별도 `@KafkaListener` 컨슈머에서 처리한다. Stateful 토폴로지에 섞지 않는다.

---

## Consequences

### Positive

- **상태 관리 내장**: RocksDB 기반 State Store 와 Kafka changelog topic 으로 장애 복구 자동.
- **윈도우 연산 완성도**: Tumbling / Hopping / Sliding / Session 윈도우 모두 지원. 향후 룰 확장이 용이.
- **Exactly-Once Semantics**: `processing.guarantee=exactly_once_v2` 옵션으로 At-Least-Once 보다 강한 보장.
- **Kafka 생태계 통합**: 별도 클러스터(Flink/Spark) 없이 Kafka 만으로 처리. 운영 컴포넌트 1개 절약.
- **트레이싱 통합 용이**: `TracingProcessor` (ADR-007) 와 함께 토폴로지에 자연스럽게 끼워 넣을 수 있다.
- **Spring Cloud Stream 호환**: `Function<KStream, KStream>` Bean 으로 노출하여 Spring 진영의 의존성 주입과 결합.

### Negative

- **RocksDB 디스크 사용량**: State Store 가 로컬 디스크에 저장된다. 윈도우 크기 × 활성 키 수에 비례해 GB 단위로 늘어날 수 있다. SSD 사양 필수.
- **Rebalance 지연**: Consumer group rebalance 시 State Store 가 다른 노드로 이동하면서 changelog 재생이 발생한다. 윈도우가 크면 분~십분 단위 지연 가능. **현재 `WINDOW_SIZE_MINUTES = 10`** 으로 영향 제한적.
- **Standby Replica 비용**: 빠른 fail-over 를 위해 `num.standby.replicas` 를 두면 디스크 + 네트워크 비용 2배.
- **디버깅 난이도**: 토폴로지는 DAG 구조라 로컬 디버깅이 어렵다. 토폴로지 시각화 (`Topology.describe()`) 와 metrics 의존도가 높다.
- **Observation 미지원**: Kafka Streams 는 Micrometer Observation 자동 전파를 지원하지 않는다 → `TracingProcessor` 수동 구현 필요 (ADR-007).
- **Kafka Streams 3.3+ API 마이그레이션**: `transformValues()` deprecated → `FixedKeyProcessor` API 로 이관. 향후 API 변경 시 코드 수정.

### Neutral

- **단일 토픽 입력**: `transfer-transaction-events` 토픽만 소비. Multi-source join 이 필요해지면 토폴로지 복잡도가 급증한다.
- **윈도우 닫힘 시점**: Tumbling window 는 매 윈도우 종료 시점에 결과를 emit. 실시간성과 정확성 사이 트레이드오프 (현재 정확성 우선).
- **`ofSizeWithNoGrace`** 선택: late event 를 받지 않는다. 시계가 흐트러지면 일부 이벤트 누락 가능. 송금 도메인은 사후 보정이 어려워 grace 를 두지 않았다.

---

## Alternatives Considered

### A. Apache Flink

- **장점**:
  - 강력한 윈도우 연산 (event-time / processing-time 자유롭게).
  - Watermark 기반 정밀한 late event 처리.
  - 대규모 클러스터 운영 실적 풍부 (Uber, Alibaba 등).
- **단점**:
  - **별도 클러스터 운영 필요** (JobManager + TaskManager).
  - Spring 생태계와의 통합이 어색 (Flink SQL / DataStream API).
  - 학습 곡선 가파름.
- **기각 사유**: 현재 트래픽(수십~수백 TPS) 대비 운영 비용 과다. 향후 수천 TPS + 복잡한 late event 처리가 필요할 때 재검토.

### B. Apache Spark Streaming

- **장점**: Spark 생태계 호환. ML 파이프라인과 결합 용이.
- **단점**:
  - Micro-batch 기반 → 진정한 streaming 이 아님. 지연 100ms~수초.
  - **별도 Spark 클러스터 필요** (Driver + Executors).
  - 윈도우 연산이 Kafka Streams 보다 덜 직관적.
- **기각 사유**: 실시간 탐지(< 1초 지연) 요구사항과 충돌.

### C. 자체 Redis Sliding Window

```kotlin
// 수신 계좌 별 ZSET 에 (timestamp, eventId) 추가
redis.zadd("recv:${receiverId}", timestamp, eventId)
// 10분 이전 데이터 제거
redis.zremrangeByScore("recv:${receiverId}", 0, now - 10.minutes)
// 현재 윈도우 카운트
val count = redis.zcard("recv:${receiverId}")
if (count >= 5) emitAlert()
```

- **장점**: Kafka Streams 학습 불필요. Redis 만 있으면 됨.
- **단점**:
  - **수신 계좌 수 × 송금 빈도** 만큼 ZSET 행이 늘어남. 메모리 폭증 위험.
  - 모든 송금마다 Redis round-trip 추가.
  - 윈도우 외 데이터 정리를 명시적으로 호출해야 함 (자동 만료 어려움).
  - State 가 Redis 에만 있어서 Redis 장애 시 탐지 불가.
- **기각 사유**: 윈도우 종류가 추가될 때마다 Redis 자료구조 설계 비용 발생. Kafka Streams 의 윈도우 API 이득이 크다.

### D. Akka Streams

- **장점**: 강력한 backpressure 제어, JVM 친화적.
- **단점**:
  - Scala 진영 친화적, Kotlin/Spring 과 결합 어색.
  - Kafka 통합이 Kafka Streams 보다 덜 자연스러움.
- **기각 사유**: 팀 기술 스택과 미스매치.

---

## Trade-offs

| 축 | Kafka Streams (채택) | Flink | Spark Streaming | Redis 자체 구현 |
|---|---|---|---|---|
| **추가 클러스터** | 불필요 | JobManager+TM | Driver+Executors | Redis (기존 활용) |
| **윈도우 표현력** | 상 (4종) | 최상 (event-time 강함) | 중 | 하 (직접 구현) |
| **실시간성** | <100ms | <100ms | 100ms~수초 | <50ms |
| **상태 저장** | RocksDB (자동) | RocksDB / Heap | Checkpoint | Redis |
| **Exactly-Once** | 지원 (`v2`) | 지원 (가장 강력) | 지원 (제한적) | 자체 구현 |
| **운영 복잡도** | 중 (Kafka 안에서 동작) | 고 | 고 | 저 (Redis만) |
| **학습 곡선** | 중 | 가파름 | 가파름 | 완만 (단, 룰 추가 비용↑) |
| **late event 처리** | grace period | watermark | watermark | 명시적 처리 |

---

## Operational Considerations

### State Store 모니터링

- `kafka-streams-state-dir-size` 메트릭으로 RocksDB 디스크 사용량 추적.
- 임계치(예: 80%) 초과 시 alert.

### Rebalance 대응

- `session.timeout.ms`, `max.poll.interval.ms` 튜닝.
- Standby replica (`num.standby.replicas=1`) 도입 시 fail-over 시간 단축. 현재 미적용.

### State 재생성 (Recovery)

- 인스턴스 디스크가 손실되면 Kafka changelog topic 에서 RocksDB 재생.
- 10분 윈도우 × 수신 계좌 1만 개 = 약 N분 재생 (벤치마크 필요).

### 토픽 파티션 수 설계

- 토픽 파티션 수 = 최대 Streams 인스턴스 수.
- 현재 `transfer-transaction-events` 파티션 = 3. Streams 인스턴스 3대까지 확장 가능.

---

## References

### 코드 경로

- 토폴로지 정의: `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/adapter/in/messaging/FraudPatternStreamProcessor.kt`
- TracingProcessor: `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/config/TracingProcessor.kt`
- application.yml: `services/fds/instances/api/src/main/resources/application.yml`
- 학습 문서: `docs/etc/kafkaStream.md`

### 외부 자료

- Apache Kafka Streams Documentation, https://kafka.apache.org/41/documentation/streams/
- William P. Bejeck Jr., *Kafka Streams in Action* (2018)
- Tyler Akidau et al., "The Dataflow Model" — 윈도우 연산 이론
- Confluent Blog, "Crossing the Streams - Joins in Apache Kafka"

---

## Related ADRs

- ADR-002: Transactional Outbox (Kafka Streams 입력 토픽이 Outbox 의 출력)
- ADR-007: Distributed Tracing (Kafka Streams 의 traceparent 수동 파싱)
