# Phase 3 회고 — Kafka Streams와 FDS 분석

> Phase 1에서 이벤트가 잘 흘러가게 됐고, Phase 2에서 그 흐름이 보이게 됐다.
> Phase 3는 그 흐름에서 "수상한 패턴"을 잡아내는 작업이다.
> 단건 룰 평가와 시간 윈도우 패턴 탐지를 분리한 게 이 Phase의 가장 중요한 결정이었다.

---

## 1. 무엇을 만들었나

Phase 3의 결과물은 세 가지다.

1. **단건 룰 기반 평가 (FDS Rule Engine)** — `AnalyzeTransferService.kt`. `HIGH_AMOUNT`, `SINGLE_HIGH_AMOUNT`, `RAPID_TRANSFER` 세 가지 룰을 단건 송금 이벤트마다 즉시 평가.
2. **10분 윈도우 패턴 탐지 (Kafka Streams)** — `FraudPatternStreamProcessor.kt`. 계좌별 10분 동안의 송금 통계를 집계해 분산송금/자금세탁 의심 패턴을 잡는다.
3. **확장점으로서의 `AiScoreProvider` 포트** — `application/required/AiScoreProvider.kt`. 현재는 `NoOpAiScoreProvider`가 0.0을 반환. ML 모델이 붙으면 어댑터만 갈아끼우면 된다.

이 세 개가 의도적으로 분리되어 있다. 단건 평가는 즉시 차단/리뷰 결정을 내고, 윈도우 집계는 사후 패턴 탐지를 한다. 둘이 같은 코드 안에 있으면 안 되는 이유가 이 회고의 핵심이다.

---

## 2. 처음 판단 — 왜 분리했나

처음에는 모든 룰을 단건 평가에 넣으려고 했다. "이벤트 하나가 들어오면 모든 조건을 확인한다"가 직관적이기 때문이다.

```kotlin
// 처음 생각했던 형태
fun analyze(event: TransferEvent): Decision {
    if (event.amount > 5_000_000) return BLOCK
    if (recentCount(event.sender, "10min") >= 5) return REVIEW  // 이게 문제
    if (foreignCountry(event)) return REVIEW
    ...
}
```

`recentCount` 부분이 문제였다. 단건 평가 안에서 "최근 10분간의 송금 횟수"를 알려면 매 송금마다 DB나 캐시를 조회해야 한다. 한 요청 처리 안에서 다른 사용자/계좌의 시간 윈도우 상태를 가져오는 건 비용이 크고, 무엇보다 **단건 평가의 핵심 책임을 흐린다**. 단건 평가는 "이 거래 하나가 이상한가"를 빠르게 판정하는 게 목적이지, "이 사용자가 최근에 이상한가"를 보는 게 아니다.

그래서 두 가지로 쪼갰다.

| 영역 | 목적 | 지연 허용 | 구현 |
|---|---|---|---|
| **단건 룰 평가** | 송금 차단/리뷰/허용 즉시 결정 | 동기 + 수십 ms 이내 | `AnalyzeTransferService` (Kafka Consumer 처리) |
| **시간 윈도우 패턴 탐지** | 분산송금/자금세탁 등 패턴 사후 탐지 | 비동기, 분 단위 지연 OK | `FraudPatternStreamProcessor` (Kafka Streams) |

이 분리가 Phase 3의 첫 결정이었다. 다음 모든 결정은 이 분리 위에서 설명된다.

---

## 3. 단건 룰 평가 — Rule Engine

### 3.1 구조

DB의 `fraud_rules` 테이블에 활성 룰이 저장되어 있다. 각 룰은 `ruleType`(예: `HIGH_AMOUNT`), `threshold`(JSON), `weight`, `severity`를 가진다. 송금 이벤트가 들어오면 모든 활성 룰을 가져와 각각 평가하고, 위반한 룰들의 `weight` 합산으로 최종 판정한다.

```kotlin
// services/fds/application/src/main/kotlin/.../AnalyzeTransferService.kt
@Transactional
fun analyze(event: TransferCompleteEvent): RiskLog {
    val activeRules = fraudRuleRepository.findAllActive()
    val ruleHits = activeRules.mapNotNull { rule -> evaluateRule(rule, event) }
    val decision = determineDecision(ruleHits)
    // ...
}

private fun determineDecision(ruleHits: List<RiskRuleHit>): FinalDecisionType {
    val totalWeight = ruleHits.sumOf { it.weight }
    return when {
        ruleHits.isEmpty() -> ALLOWED
        totalWeight >= 100 -> BLOCKED
        totalWeight >= 50 -> REVIEW
        else -> ALLOWED
    }
}
```

이 구조가 좋았던 점은 **새 룰을 추가할 때 코드 변경이 최소**라는 것이다. 룰 타입을 한 줄 추가하면 된다.

```kotlin
private fun evaluateRule(rule: FraudeRule, event: TransferCompleteEvent): RiskRuleHit? {
    return when (rule.ruleType) {
        "HIGH_AMOUNT" -> checkHighAmount(rule, event)
        "SINGLE_HIGH_AMOUNT" -> checkSingleHighAmount(rule, event)
        "RAPID_TRANSFER" -> checkRapidTransfer(rule, event)
        else -> null  // 미지원 룰 타입은 무시
    }
}
```

### 3.2 RAPID_TRANSFER가 단건 평가에 남은 이유

여기서 결정 하나가 흔들렸다. `RAPID_TRANSFER`는 "최근 N분 내 송금 횟수"를 보는 룰이다. 위에서 "시간 윈도우는 Streams에서"라고 분리했는데, 이 룰만은 단건 평가에 남았다.

이유는 **차단 시점의 즉시성** 때문이다. RAPID_TRANSFER는 "이 거래가 지금 통과되면 안 된다"는 판정이 필요한 룰이다. Streams로 가면 분 단위 지연이 생기고, 그 사이 송금은 이미 처리됐다. 사후 알람만 띄울 수 있다.

대신 RAPID_TRANSFER는 DB의 `transactions` 테이블을 직접 조회한다.

```kotlin
private fun checkRapidTransfer(rule: FraudeRule, event: TransferCompleteEvent): RiskRuleHit? {
    val since = event.occurredAt.minusSeconds(windowMinutes * 60)
    val count = recentTransferCountQueryPort.countByUserSince(event.senderId, since)
    return if (count > maxCount) RiskRuleHit(...) else null
}
```

`RecentTransferCountQueryPort`는 application 레이어에 정의된 포트이고, 구현체는 `JpaRecentTransferCountQueryAdapter`다. 인덱스 `idx_tx_sender_created (sender_user_id, created_at DESC)`를 활용해 빠르게 카운트한다.

여기에 처음에 NoOp 어댑터를 붙였다가 한참 그대로 둔 적이 있다. 이게 실수였다. 뒤에서 다룬다.

---

## 4. 시간 윈도우 패턴 탐지 — Kafka Streams

### 4.1 왜 Kafka Streams였나

윈도우 집계를 할 후보가 여러 개 있었다.

| 도구 | 장점 | 단점 |
|---|---|---|
| **Kafka Streams** | Kafka 토픽을 직접 입력으로, State Store 내장, Spring Boot 통합 | 외부 분산 처리는 약함, Flink 대비 표현력 낮음 |
| **Apache Flink** | 윈도우 표현력 풍부, 정확한 event-time 처리, exactly-once | 별도 클러스터, 운영 복잡도, Spring 통합 약함 |
| **Spring Batch + scheduling** | 운영 단순 | 실시간성 없음 (배치 주기에 지연) |
| **자체 구현 (Redis sorted set)** | 가벼움 | 윈도우 경계, 만료, GC 다 직접 구현 |

Flink가 표현력은 가장 좋다. 그런데 Flink JobManager/TaskManager 클러스터를 운영하는 비용이 토이 프로젝트 규모에서는 정당화되지 않았다. Kafka Streams는 **별도 클러스터 없이** 애플리케이션 안에서 동작한다. Kafka 토픽이 이미 있고, Spring Boot Application 안에서 토폴로지를 정의하면 된다. 학습 곡선도 낮았다.

자체 구현은 처음부터 고민하지 않았다. 윈도우 만료, 재처리, exactly-once를 직접 다루는 건 사고가 나기 좋은 영역이다.

### 4.2 토폴로지 — 단순 집계의 함정

`FraudPatternStreamProcessor`의 토폴로지는 단순하다.

```
input(transfer-events)
  → selectKey(receiverId)
  → groupByKey()
  → windowedBy(10분 Tumbling)
  → aggregate(count, totalAmount)
  → filter(count >= 5 || totalAmount >= 20,000,000)
  → mapValues(SuspiciousPatternAlert)
  → output(suspicious-patterns)
```

10분 동안 같은 계좌로 (a) 5건 이상 송금이 들어오거나, (b) 총 2천만 원 이상 송금이 들어오면 의심 패턴으로 분류한다. 이게 분산송금/자금세탁 시그널의 기본 형태다.

여기서 처음에 놓친 게 두 가지였다.

**(1) Tumbling Window의 경계 효과.** 10분 Tumbling은 [00:00~00:10), [00:10~00:20)처럼 겹치지 않는 윈도우다. 만약 누군가 00:09에 4건, 00:11에 4건을 송금하면 어느 윈도우에서도 "5건"이 안 잡힌다. 의도적으로 회피하는 패턴이라면 이 경계가 약점이다. 이걸 막으려면 Hopping Window (예: 10분 윈도우를 1분마다 이동)나 Sliding Window가 필요하다. 현재는 그대로 Tumbling을 쓰고 있고, 이건 알면서 둔 트레이드오프다. 향후 개선 항목.

**(2) State Store 디스크 사용량.** Kafka Streams의 윈도우 집계는 RocksDB 기반 State Store에 상태를 저장한다. 윈도우가 expire되어도 retention 정책 안에서는 디스크에 남는다. 처음에 retention을 신경 안 쓰고 default(24시간)로 두니까, 며칠 운영 후 디스크 사용량이 의외로 컸다. 윈도우가 10분이라면 retention은 길어야 30분~1시간이면 충분하다.

---

## 5. 무엇이 잘못됐나 — 세 가지 미스

### 5.1 detectSuspiciousPatterns가 한동안 비활성

Streams 토폴로지를 만들어 두고, application.yml의 spring.cloud.stream.function.definition에서 해당 함수를 등록하는 걸 빠뜨렸다. Spring Cloud Stream functional binding에서 함수 이름을 명시하지 않으면 토폴로지가 시작되지 않는다.

```yaml
# 빠져 있던 부분
spring:
  cloud:
    function:
      definition: detectSuspiciousPatterns  # ← 이게 없었다
```

코드는 다 있는데 동작하지 않는 상태가 며칠 갔다. 로그에 에러가 안 떴기 때문에 더 늦게 발견했다. **Spring Cloud Stream의 functional 모델은 "선언만으로 자동 동작하지 않는다"**. 명시적 enrolling이 필요하다. 이 단순한 사실을 매뉴얼에서 한 번 더 봤어야 했다.

### 5.2 출력 토픽 컨슈머 부재로 알림 유실

이게 더 큰 미스였다. Streams가 의심 패턴을 감지하면 `suspicious-patterns` 토픽으로 메시지를 발행한다. 그런데 **그 토픽을 소비하는 컨슈머가 한동안 없었다**.

```
Streams → suspicious-patterns 토픽 (메시지 발행) → ???
```

토픽에 메시지가 쌓이고 있었는데, 그걸 받아서 DB에 저장하거나 알림을 보내는 컴포넌트가 없었다. retention 기간이 지나면 메시지가 사라진다. 며칠 동안 이 상태였다.

`SuspiciousPatternAlertConsumer`를 별도로 만들고 `@KafkaListener`로 토픽을 구독해 `suspicious_pattern_alerts` 테이블에 영속화하는 흐름을 추가했다.

```kotlin
// services/fds/infra/src/main/kotlin/.../SuspiciousPatternAlertConsumer.kt
@KafkaListener(topics = ["suspicious-patterns"])
fun consume(message: String) {
    val alert = objectMapper.readValue<SuspiciousPatternAlert>(message)
    suspiciousPatternAlertRepository.save(alert.toEntity())
}
```

여기서 배운 건 **"Producer는 만들었지만 Consumer는 잊었다"**는 패턴이다. Kafka 토픽은 양쪽이 다 있어야 의미가 있다. 토픽을 새로 만들 때 Producer와 Consumer를 동시에 그리는 습관이 필요했다.

### 5.3 RAPID_TRANSFER NoOp 어댑터 잔존

`RecentTransferCountQueryPort`의 첫 구현은 NoOp이었다.

```kotlin
class NoOpRecentTransferCountQueryAdapter : RecentTransferCountQueryPort {
    override fun countByUserSince(userId: Long, since: Instant): Long = 0L  // 항상 0
}
```

이게 한동안 그대로 있었다. 즉, RAPID_TRANSFER 룰이 평가될 때 항상 "최근 송금 0건"으로 계산됐고, `count > maxCount` 조건은 영원히 false였다. **룰은 활성화되어 있는데 실제로는 한 번도 위반을 감지하지 못했다.**

`JpaRecentTransferCountQueryAdapter`로 교체해서 풀었다. 인덱스 (sender_user_id, created_at DESC)를 미리 만들어 둔 게 도움이 됐다.

여기서 가장 큰 교훈은 **NoOp 어댑터가 "임시"라는 단서를 코드에 박지 않으면 영원히 NoOp으로 산다**는 것이다. Phase 2의 일일 한도 비활성 코드와 정확히 같은 패턴이다. 같은 실수를 두 번 했다.

---

## 6. AiScoreProvider — 확장점으로서의 빈 슬롯

ML 점수를 fraud 판정에 어떻게 통합할지는 처음부터 고민이었다. 결정은 **"지금은 빈 슬롯으로 두되, 인터페이스만 정의하자"**였다.

```kotlin
// services/fds/application/src/main/kotlin/.../AiScoreProvider.kt
interface AiScoreProvider {
    fun score(event: TransferCompleteEvent): Double  // 0.0 ~ 1.0
}

// services/fds/instances/api/src/main/kotlin/.../NoOpAiScoreProvider.kt
@Component
class NoOpAiScoreProvider : AiScoreProvider {
    override fun score(event: TransferCompleteEvent): Double = 0.0
}
```

이 결정이 옳았다고 본다. 이유는 두 가지다.

1. **포트가 있다는 사실 자체가 "여기에 ML이 들어올 수 있다"는 문서가 된다.** 다른 사람이 이 코드를 보면 "ML 통합은 이 인터페이스를 구현하면 된다"는 걸 즉시 안다.
2. **NoOp 구현체가 있어서 시스템이 ML 없이도 완전히 동작한다.** ML 모델은 학습/배포 사이클이 길다. 그동안 시스템이 멈추면 안 된다. NoOp이 0.0을 반환하는 게 곧 "ML 영향 없이 룰만으로 판정한다"는 뜻이다.

다만 NoOp 패턴은 위 5.3에서 본 것처럼 위험하다. AiScoreProvider는 빈 슬롯이라는 게 명확하므로 NoOp이 잘못된 것이 아니다. 반면 RecentTransferCountQueryPort는 실제 동작이 필요한 컴포넌트인데 NoOp이 박혀 있었던 게 잘못이다. **"빈 슬롯으로서의 NoOp"과 "임시방편 NoOp"을 구분해서 다뤘어야 했다.**

이 깨달음을 코드에 반영하려면, "임시방편 NoOp"에는 `@Deprecated` 또는 컴파일 타임 경고를 박는 게 맞다고 본다. 향후 개선 항목.

---

## 7. 메트릭 요약

| 영역 | 지표 | 결과 |
|---|---|---|
| 단건 룰 평가 | 평균 처리 RT | 10~30ms (활성 룰 3개 기준) |
| 단건 룰 평가 | DB 조회 | RAPID_TRANSFER 룰 평가 시 인덱스 카운트 1회 |
| Streams 윈도우 | 처리 지연 (Producer commit ~ Aggregate emit) | 수십 초 (Tumbling 윈도우 특성) |
| State Store | 디스크 사용량 (10분 윈도우, 1시간 retention) | 토픽 메시지 양에 선형 비례, 평상시 수십 MB |
| Suspicious Pattern | 알림 영속화 ratio | 100% (Consumer 추가 이후) |

> 처리 지연을 "수십 초"라고 적은 이유: Tumbling Window는 윈도우가 닫히는 시점에만 결과를 emit한다. 즉, 10분 윈도우라면 첫 이벤트가 들어와도 윈도우가 닫혀야 결과가 나간다. 이건 Streams의 동작 방식이지 우리 코드의 문제는 아니다.

---

## 8. 내가 했던 실수

**(1) "코드만 짜면 자동 동작"이라고 가정했다.** Spring Cloud Stream functional binding에서 함수를 명시적으로 enroll하지 않으면 토폴로지가 시작되지 않는다. 로그에 에러도 안 떴다. 며칠 후에 발견했다. **에러가 없다고 동작한다는 게 아니다.**

**(2) Producer만 만들고 Consumer를 안 만들었다.** suspicious-patterns 토픽으로 메시지가 발행됐지만 받는 쪽이 없었다. retention 지나면 사라진다. Kafka 토픽은 양쪽이 다 있어야 의미가 있다. 토픽을 만드는 순간 Producer와 Consumer 양쪽을 같이 그리는 습관이 필요했다.

**(3) NoOp 어댑터를 한 달 가까이 방치했다.** RAPID_TRANSFER 룰은 활성화되어 있는데 항상 "0건"으로 평가되고 있었다. Phase 2의 일일 한도 NoOp과 정확히 같은 실수를 반복했다. **같은 실수를 두 번 한 건 패턴 인식에 실패했기 때문이다.**

**(4) Tumbling Window의 경계 효과를 미리 따져보지 않았다.** 10분 경계 직전/직후에 거래를 분산하는 패턴이 우회한다. 이건 알면서 둔 트레이드오프지만, 처음부터 알았던 게 아니라 누군가 지적해 줘서 알게 됐다. 윈도우를 고를 때 회피 패턴까지 생각했어야 했다.

**(5) State Store retention을 default로 둬서 디스크가 의외로 컸다.** 윈도우 길이와 retention 길이의 관계를 처음부터 명시했어야 했다. Kafka Streams 매뉴얼이 이걸 강조하지 않는 게 아쉽다.

**(6) Streams 3.3+의 deprecated API 마이그레이션을 미뤘다.** `transformValues()`에서 `FixedKeyProcessor`로 옮기는 작업이다. 빌드 경고를 무시했다가 한 번 호되게 당했다.

---

## 9. 남은 리스크 / 다음 단계

- **State Store의 백업 전략이 없다.** RocksDB는 로컬 디스크에 있고, 그 디스크가 손실되면 윈도우 상태가 사라진다. Kafka Streams는 standby replica를 지원하지만 운영 비용이 추가된다.
- **윈도우 회피 패턴이 가능하다.** Tumbling 10분의 경계 효과. Hopping Window 또는 Sliding Window로 옮겨가야 한다.
- **AiScoreProvider 구현체가 NoOp이다.** ML 모델 학습 데이터셋, 추론 인프라(별도 서비스 또는 ONNX runtime 내장), 모델 버저닝까지 후속 작업이 많다.
- **Streams의 backpressure 대응이 검증되지 않았다.** 입력 토픽이 폭증할 때 Streams가 어떻게 buffer되고 어디서 lag이 쌓이는지 부하 테스트로 확인이 필요하다.
- **알림 채널이 DB뿐이다.** Slack/이메일 등 push 알림이 없다. 운영자가 적극적으로 조회해야 알아챈다.

---

## 10. 빅테크 인터뷰 Q&A

### Q1. 왜 Apache Flink가 아니라 Kafka Streams를 선택했나?

운영 비용과 학습 곡선이 결정 기준이었다. Flink는 윈도우 표현력이 가장 풍부하다(event-time, watermark, allowed lateness 등이 정교하다). 그런데 Flink는 JobManager/TaskManager로 별도 클러스터를 운영해야 한다. 토이 프로젝트 규모에서 이건 정당화되지 않았다. Kafka Streams는 Spring Boot 애플리케이션 안에서 동작하고, Kafka 토픽을 입력/출력으로 직접 쓴다. State Store도 내장이다. **트래픽 규모가 커지고 정교한 event-time 처리가 필요해지면 Flink가 자연스러운 다음 스텝이다.** 지금은 Streams로 충분하다는 게 합리적 판단이었다.

### Q2. Kafka Streams의 State Store(RocksDB) 백업 전략은?

현재는 백업 전략이 없다. 이게 알면서 둔 한계다. Kafka Streams는 standby replica를 설정할 수 있고, changelog 토픽으로 상태를 Kafka에 복제한다(`KTABLE-STATE-STORE-CHANGELOG`). 이게 실질적인 백업 역할을 한다. 즉, "RocksDB 디스크 손실 시 changelog 토픽에서 복구"가 표준 경로다. 다만 large state store는 복구 시간이 길어지는 게 트레이드오프다. 운영 환경에서는 (a) standby replica를 둬서 failover 시간을 줄이고, (b) changelog 토픽의 retention을 충분히 잡는 게 표준이다. 현재 시스템은 단일 인스턴스라 이 부분이 약점이다.

### Q3. RocksDB 운영 이슈는 어떤 게 있나?

가장 큰 건 **디스크 사용량 예측이 어렵다**는 점이다. RocksDB는 LSM-tree 기반이라 write amplification이 있고, compaction이 일어나기 전까지는 디스크에 중복 데이터가 남는다. 윈도우 retention이 길수록 이게 누적된다. 두 번째는 **memtable 메모리**다. 기본 설정이 의외로 크다. State Store가 여러 개면 메모리가 빨리 차오른다. 우리는 RocksDBConfigSetter로 memtable 크기를 명시적으로 줄였다. 세 번째는 **JNI 비용**이다. RocksDB는 native이므로 JVM heap이 아니라 off-heap을 쓴다. 컨테이너 메모리 limit 설정 시 heap만 보고 잡으면 OOM이 난다. Kubernetes로 옮길 때 이 부분을 따로 챙겨야 한다.

### Q4. Tumbling Window의 경계 효과를 어떻게 막을 건가?

세 가지 옵션이 있다. (a) **Hopping Window**로 바꾸기. 10분 윈도우를 1분 간격으로 이동시키면 사실상 모든 10분 구간이 평가된다. 단, 같은 이벤트가 여러 윈도우에 속하므로 결과 양이 늘고 중복 알림을 dedup해야 한다. (b) **Sliding Window** (Streams 2.7+). 이벤트 시점 기준으로 윈도우가 형성된다. 가장 정확하지만 State Store 비용이 가장 크다. (c) **현재 Tumbling을 유지하면서 RAPID_TRANSFER 같은 단건 룰로 보완**. 우리는 (c)에 가깝다. Tumbling이 미세한 경계 회피를 잡지 못해도 RAPID_TRANSFER(DB 직접 쿼리)가 1분/5분 단위로 catch하므로, 실질적 검출률은 충분하다고 판단했다. 다만 정확도가 더 필요해지면 Hopping으로 가는 게 답이다.

### Q5. Rule Engine을 DSL이나 외부 도구(Drools 등)로 옮길 계획은?

지금은 안 옮긴다. 이유는 두 가지다. (a) **룰 수가 적다.** 3~5개 수준이면 Kotlin when 분기가 가장 읽기 쉽다. Drools 같은 도구는 룰이 수십~수백 개로 늘었을 때 가치가 있다. (b) **룰이 데이터 의존적이다.** RAPID_TRANSFER는 DB 카운트가 필요하다. Drools 같은 도구에서 외부 데이터 접근은 가능하지만 trickier하다. 그래서 현재 구조 — 룰 메타데이터는 DB에, 평가 로직은 Kotlin 코드에, 새 룰 타입은 `when` 분기 추가 — 가 가장 정직한 형태다. **룰이 50개를 넘어가는 시점에 DSL을 검토하는 게 맞다고 본다.** 그전에는 코드가 가장 빠르다.

---

## 관련 코드 경로

- `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/service/AnalyzeTransferService.kt`
- `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/required/AiScoreProvider.kt`
- `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/required/RecentTransferCountQueryPort.kt`
- `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/required/FraudRuleRepository.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/adapter/in/messaging/FraudPatternStreamProcessor.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/adapter/in/messaging/SuspiciousPatternAlertConsumer.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/adapter/in/messaging/TransferEventConsumer.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/rdb/adapter/JpaRecentTransferCountQueryAdapter.kt`
- `services/fds/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/container/adapter/NoOpAiScoreProvider.kt`
- `services/fds/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/container/config/KafkaListenerConfig.kt`
- Kafka Streams 학습 정리: `docs/etc/kafkaStream.md`
