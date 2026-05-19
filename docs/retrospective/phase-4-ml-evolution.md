# Phase 4 회고 — AiScoreProvider 의 진화

> Phase 3 가 끝났을 때 `AiScoreProvider` 자리에는 `NoOpAiScoreProvider` 하나만 있었어요.
> `override fun score(event): Double? = null` 한 줄.
> Phase 4 에서 그 자리에 두 어댑터를 들였습니다. Heuristic 과 Statistical.
> 들이고 나서 "이걸 ML 이라고 부르는 게 정직한가" 가 가장 오래 남는 의심이었어요.

---

## 1. 시작 시점에 있던 자리

Phase 3 회고 6장에 적어둔 그대로, `AiScoreProvider` 는 "여기에 ML 이 들어올 수 있다"는 인터페이스만 있는 빈 슬롯이었어요.

```kotlin
interface AiScoreProvider {
    fun score(event: TransferCompleteEvent): Double?
}

@Component
class NoOpAiScoreProvider : AiScoreProvider {
    override fun score(event: TransferCompleteEvent): Double? = null
}
```

Phase 3 가 끝나는 시점에는 이 구조에 만족하고 있었습니다.
포트가 있다는 사실이 곧 "ML 통합 자리" 라는 의도를 코드로 남긴다고 적었고, 그 판단은 지금도 유지하고 있어요.

Phase 4 에서 그 자리에 실제로 무언가를 넣어야 했는데, 가장 먼저 부딪힌 게 "무엇을 넣을 것인가" 가 아니라 "왜 지금 ML 인가" 였어요.

---

## 2. 왜 외부 ML 안 쓰고 휴리스틱부터 시작했나

처음 후보는 외부 ML SaaS 였어요. AWS Fraud Detector, Sift, Riskified 같은 것들.
인터페이스가 다 잡혀 있고, REST 한 번 쏘면 score 가 돌아오는 구조라 어댑터 한 장만 쓰면 됐어요. `RestTemplate` 하나로 끝나는 일.

근데 시작 직전에 세 가지가 동시에 막혔어요.

**(1) 학습 데이터가 0 이다.**
외부 ML SaaS 도 결국 우리 데이터로 fine-tuning 해야 의미가 있어요. 그런데 `transactions` 테이블에 `is_fraud` 라벨이 없습니다. 운영자 검토 결과를 기록하는 자리가 아예 없어요. 라벨 없는 상태로 SaaS 에 데이터를 넣으면 결국 그쪽이 가진 일반 모델이 돌고, 그건 우리 도메인 특성을 모르는 모델이에요.

**(2) latency 와 비용 부담.**
송금 API 가 동기 경로라 외부 호출 한 번이 그대로 응답 시간에 더해져요. Sift docs 기준 p95 가 100~300ms 인데, 우리 송금 API 의 현재 p95 가 비슷한 자리예요. 한 자리에 두 배가 되는 일을 부하 테스트 없이 들이긴 어려웠습니다.
비용도 비슷한 결이에요. 토이 프로젝트 규모에서 거래당 과금 모델은 정당화가 안 됐어요.

**(3) "이 사이드 프로젝트가 진짜 ML 이 필요한가" 자기 의심.**
이게 가장 컸어요.
Phase 3 의 룰 엔진이 이미 `HIGH_AMOUNT`, `SINGLE_HIGH_AMOUNT`, `RAPID_TRANSFER` 세 룰을 평가하고 있고, Kafka Streams 가 윈도우 집계로 패턴까지 잡고 있어요. 여기에 ML 을 들이는 게 정말 필요해서 들이는 건지, 아니면 "ML 자리가 있으면 멋있어 보이니까" 들이는 건지 한참 모호했습니다.

세 가지가 동시에 사실이었기 때문에, 외부 ML SaaS 대신 휴리스틱부터 들이기로 했어요.
완전한 답은 아니지만 — 학습 데이터 없는 상태에서 외부 ML 을 들이는 것보다, 내가 가중치를 정직하게 박은 휴리스틱이 더 정직한 자리라고 봤습니다.

---

## 3. HeuristicAiScoreAdapter 를 적으면서 든 의심

코드는 단순해요. 가중합 + sigmoid.

```kotlin
val raw = amountFeature(event.amount) +
          hourFeature(event) +
          velocityFeature(event)
val score = 1.0 / (1.0 + exp(-(raw - 0.5) * 4.0))
```

`amountFeature` 는 log10 amount 에 따라 0 / 0.1 / 0.25 / 0.4.
`hourFeature` 는 02~05 KST 새벽이면 0.2.
`velocityFeature` 는 최근 5분 송금 횟수에 따라 0 / 0.15 / 0.3.

코드 적는 동안 머리에 계속 남는 의심이 두 개였어요.

**의심 1: 0.4 / 0.25 / 0.1 / 0.2 / 0.3 / 0.15 — 이 숫자들 다 내 직관이다.**
왜 100만원 이상이 0.25 이고 1천만원 이상이 0.4 인가. 왜 0.3 이 아니고 0.4 인가.
정답은 없어요. "이 정도 차이가 의심도 차이를 표현한다고 내가 느끼는 자리" 라고 적을 수밖에 없어요. 데이터 기반으로 튜닝된 값이 아니에요.

**의심 2: 사람 직관 + sigmoid = ML 인가, 아니면 그냥 룰 엔진의 확장인가.**
sigmoid 한 줄을 추가했다고 해서 ML 이 되는 건 아니에요. sigmoid 는 단지 가중합을 0~1 로 압축하는 함수일 뿐이고, 가중치가 학습된 게 아니라 박힌 거라면 그건 룰 엔진이에요. 룰 평가 결과가 score 라는 한 숫자로 합쳐지는 것뿐.

이 자리에서 결국 인정한 게 있어요.
**학습 없는 휴리스틱은 룰 엔진의 확장이다.** "ML PoC 1단계" 라는 이름은 그래서 미래의 어댑터 — `IsolationForestAiScoreAdapter` 같은 — 가 들어올 자리를 미리 비워두는 의도이지, 지금 이게 ML 이라는 주장이 아니에요.

코드 클래스 주석에 그 인정을 박았어요.

```kotlin
/**
 * 휴리스틱 기반 anomaly score (ML PoC 1단계).
 * ...
 * 한계 (E6 RETRO 에 명시):
 *  - 가중치 자체가 사람 직관. 학습 없음.
 */
```

이 주석을 적고 나서야 다음 어댑터로 넘어갈 수 있었어요.

---

## 4. StatisticalAiScoreAdapter 를 들이면서 든 의심

Heuristic 이 "전체 평균 사용자" 를 가정한다면, Statistical 은 "이 사용자의 평소 패턴 대비 이번 거래가 얼마나 벗어났나" 를 봅니다.
사용자별 30일 송금액의 로그 평균/표준편차로 z-score 를 내고 sigmoid 로 압축.

```kotlin
val logAmount = ln(event.amount.coerceAtLeast(1L).toDouble())
val zAbs = abs(logAmount - mean) / std
val score = 1.0 / (1.0 + exp(-(zAbs - 2.0) * 1.5))
```

이쪽은 적어도 "데이터 본다" 는 말은 할 수 있어요. 그래도 의심이 세 개 남았어요.

**(1) cold start 문제.**
신규 사용자는 30일치 데이터가 없어요. 코드에서는 `count < 5` 면 0.0 을 반환해요.

```kotlin
if (stats.count < MIN_SAMPLES) return 0.0 // cold start
```

이게 옳은가. 신규 사용자에게 0.0 을 반환한다는 건 "패턴 정보 없음 = 의심 없음" 으로 보는 건데, 사실 fraud 의 상당수가 신규 계정에서 일어나요. 신규 사용자한테 오히려 score 를 높게 줘야 하는 자리일 수도 있고, 아니면 Heuristic 결과를 fallback 으로 써야 하는 자리일 수도 있어요. 지금은 그냥 0.0 으로 두고 RETRO 에 적어뒀습니다.

**(2) 30일 롤링 쿼리가 매 분석마다 도는 부하를 측정 안 했다.**

```sql
SELECT COUNT(*), AVG(LN(GREATEST(amount, 1))), STDDEV(LN(GREATEST(amount, 1)))
FROM transactions
WHERE sender_user_id = ?
  AND status = 'COMPLETED'
  AND created_at >= now() - INTERVAL '30 days'
```

`(sender_user_id, status, created_at)` 인덱스가 도와주긴 할 텐데, 송금 한 건당 이 쿼리가 한 번씩 도는 게 실제로 어느 만큼의 부하인지 부하 테스트로 확인하지 않았어요. E6 가 이걸 잡아야 할 자리인데, 지금 시점에는 코드만 들이고 측정은 미뤘습니다.

**(3) 다변량이 아니다. 금액 한 변수만 본다.**
시간/빈도 동시 이상은 못 잡아요. 평소 평일 낮에 적은 금액으로 송금하던 사용자가 새벽에 평소 금액의 큰 금액을 빠르게 여러 건 보내면, Heuristic 은 새벽 + 금액 + 속도 세 자리에서 모두 점수를 올리는데, Statistical 은 z-score 만 보니까 "금액이 평소보다 크다" 한 자리에서만 점수가 올라요.

진짜로 이걸 anomaly detection 이라고 부르려면 multivariate 가 필요해요. Isolation Forest 같은 거. 근데 그건 학습 데이터가 있어야 시작이 되는 자리예요. 다시 (2장의) 처음 의심으로 돌아갑니다.

---

## 5. 측정하지 않은 자리 (E6 와 연결)

두 어댑터 다 메트릭만 박았어요.

```kotlin
private val scoreDistribution: DistributionSummary = DistributionSummary.builder("fds.ai.score")
    .description("Heuristic AI score 분포 (0~1)")
    .baseUnit("score")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(meterRegistry)
```

`fds.ai.score` Distribution metric 으로 score 분포가 잡혀요. 어느 어댑터를 active 하느냐에 따라 분포가 달라질 거예요.

그런데 두 가지를 측정 안 했어요.

**(1) score 가 실제로 의심 거래를 가려내는가.**
정밀도 / 재현율을 잴 수가 없어요. `is_fraud` 라벨이 없으니까. 그래서 E6 의 가설은 "분포만 본다" 입니다. 정밀도/재현율은 라벨 데이터를 수집한 다음 라운드의 자리예요.

**(2) 어댑터 도입 후 송금 분석 처리량 영향.**
Heuristic 은 `recentTransferCountQueryPort.countByUserSince` 한 번 호출, Statistical 은 30일 롤링 쿼리 한 번 호출. 둘 다 분석 경로에 비용이 추가돼요. 부하 테스트는 다음 자리로 미뤘는데, 이게 ADR-011 의 약점이에요. "코드 들이고 운영 영향 안 잰" 케이스.

---

## 6. 다음 단계로 미룬 것들

회고를 적으면서 남는 항목이 네 개예요.

**(1) 실 fraud 라벨 수집.**
운영자 검토 화면이 필요해요. RiskLog 에 운영자가 "이거 진짜 fraud", "오탐" 을 박는 자리. 그 데이터가 쌓여야 정밀도/재현율 측정이 시작돼요. 라벨 1000건이면 어떤 모델이든 학습이 시작될 수 있는 규모로 봐요.

**(2) Isolation Forest 도입.**
다변량 anomaly detection 의 가장 단순한 형태. JVM 내 옵션으로 Smile 라이브러리가 있고, 외부 ML 서비스로 분리하는 옵션으로 Python + scikit-learn 컨테이너를 띄우는 길이 있어요. 후자가 정석이지만, 우선 Smile 로 in-process PoC 가 가능한지부터 봐야 할 자리예요.

**(3) ES dense_vector kNN PoC.**
거래 벡터화 + Elasticsearch dense_vector + kNN 으로 "이 거래와 가장 가까운 과거 거래 N 건" 을 찾는 접근. 이건 라벨이 없어도 시작할 수 있어요. fraud 라벨이 붙은 과거 거래와 비슷한 거래에 가산점을 주는 방식. 설계는 `docs/etc/ml-anomaly-detection-poc.md` 에 잡혀 있고, E7 가 이걸 검증할 자리로 후속 실험 후보에 들어가 있어요.

**(4) decisionWeight 와 aiScore 통합 정책.**
지금은 분리되어 있어요. 룰 위반 weight 합산이 `BLOCKED / REVIEW / ALLOWED` 를 결정하고, `aiScore` 는 별도로 `RiskLog` 에 저장만 돼요. 결정에 영향 안 줘요. 이게 의도된 자리인데 — score 를 결정 경로에 합치려면 (a) score 임계치를 별도 룰로 만들거나 (b) totalWeight 에 score × N 을 더하거나 (c) score 가 일정 이상이면 REVIEW 로 escalate 시키거나 셋 중 하나의 정책 결정이 필요해요. 지금은 이 결정을 안 했어요. 측정 없이 정책부터 들이고 싶지 않아서요.

---

## 7. 남은 인정

Phase 3 회고 6장에서 "빈 슬롯으로서의 NoOp" 과 "임시방편 NoOp" 을 구분했어요.
Phase 4 가 끝난 지금, `AiScoreProvider` 자리는 더 이상 빈 슬롯이 아니에요. 두 어댑터가 들어있고 ConditionalOnProperty 로 골라 쓸 수 있어요.

근데 동시에 정직하게 인정할 자리도 있습니다.
**Heuristic 도 Statistical 도 측정 없이 들였어요.**
ADR-009 가 "직관/관습이 아닌 데이터가 결정의 근거" 라고 말하고 있는데, Phase 4 의 두 어댑터는 데이터로 결정된 게 아니에요. 가중치도 직관, threshold 도 직관, sigmoid scale 도 직관.

이게 ADR-011 의 가장 큰 약점이고, E6 의 존재 이유예요.
E6 는 측정 안 한 자리를 메우러 가는 실험인데, 그조차도 라벨 부재로 "분포만 본다" 가 한계예요. 진짜 검증은 라벨 데이터가 쌓이고 나서야 시작돼요.

Phase 5 에서 이걸 풀러 가야 할 자리로 봅니다.

---

## 관련 코드 경로

- `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/required/AiScoreProvider.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/ai/HeuristicAiScoreAdapter.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/ai/StatisticalAiScoreAdapter.kt`
- `services/fds/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/container/adapter/NoOpAiScoreProvider.kt`

## 관련 ADR / 실험

- ADR-011 AiScoreProvider 진화 전략 (Heuristic → Statistical → 외부 ML) — 본 Phase 의 메타
- ADR-009 실험 주도 의사결정 — Phase 4 의 약점이 인정되는 자리
- [E6 AiScoreProvider 진화](../experiments/E6-ai-score-evolution/) — 본 회고의 측정 자리
- `docs/etc/ml-anomaly-detection-poc.md` — ES dense_vector kNN 설계 (E7 후보)
