# ADR-011: ML 기반 이상행위 점수 어댑터의 단계적 진화

- **Status**: Accepted (Phase 1) / Proposed (Phase 2~4 전환 후보)
- **Date**: 2026-05-20
- **Tags**: fds, ml, anomaly-detection, adapter-evolution, hexagonal

---

## Context

FDS 는 지금까지 `HIGH_AMOUNT`, `SINGLE_HIGH_AMOUNT`, `RAPID_TRANSFER` 같은 임계값 룰로 거래를 판정해요. 이게 잘 잡는 자리도 있는데, 다음 세 자리에서는 룰만으로는 부족하다는 게 사용 중에 드러났습니다.

- **임계값 분산 회피**: 1,000만원 룰을 피하려고 990만원으로 9번 보내는 식의 smurfing 은 단일 거래 임계로는 안 잡혀요.
- **사용자별 패턴 차이**: 평소 5만원만 보내던 사용자의 80만원 거래와 평소 100만원 단위로 보내던 사용자의 80만원 거래는 다른 신호인데, 단일 임계는 둘을 똑같이 봐요.
- **다변량 상호작용**: 금액 + 시간대 + 빈도가 동시에 평소와 어긋난 경우, 각 축의 임계는 안 넘어도 합쳐서 보면 분명한 이상인 자리가 있어요.

이 한계를 어디까지 ML 로 메울 건지가 이 ADR 의 주제입니다.

다만 솔직하게 적자면 — **우리 프로젝트는 지금 fraud 라벨 데이터가 0건이에요.** 학습 기반 모델을 "지금 들이자" 라고 단정 못 하는 자리가 그 부분입니다. 그래서 이 ADR 은 "ML 을 한 번에 박는 결정" 이 아니라, **어댑터를 단계로 갈아끼는 경로** 를 박는 결정으로 갑니다.

확장점은 이미 만들어 뒀어요. `application/required/AiScoreProvider.kt` 가 포트고, `AnalyzeTransferService` 는 이 포트에만 의존합니다. 어댑터를 갈아끼우는 비용은 Spring `@ConditionalOnProperty` 한 줄이에요.

## Decision

`AiScoreProvider` 의 구현체를 다음 4단계로 진화시킵니다. 각 단계는 어댑터 한 개씩.

### Phase 0 — NoOp (지금까지의 상태)

- 구현: `NoOpAiScoreProvider.score() = null`.
- 의도: AiScoreProvider 인터페이스만 도입하고, 실제 점수 산출은 안 함. RiskLog.aiScore 는 항상 null.
- 비용: 거의 0.
- 한계: 룰 기반과 동일. ML 의 어떠한 이점도 없음.

### Phase 1 — Heuristic 어댑터 (지금 적용한 자리)

- 구현: `HeuristicAiScoreAdapter`. 룰 3개 가중합 + sigmoid 로 0~1 압축.
  - 금액 feature: `log10(amount)` 가 7 이상이면 +0.4, 6 이상이면 +0.25, 5 이상이면 +0.1.
  - 시간 feature: 02~05 KST 새벽이면 +0.2.
  - 속도 feature: 최근 5분 송금 횟수 ≥ 5 면 +0.3, ≥ 3 이면 +0.15.
  - 합산값 `raw` 에 sigmoid `1 / (1 + exp(-(raw - 0.5) * 4))` 를 씌워 0~1.
- 활성화: `fds.ai.score-provider=heuristic` (default, matchIfMissing=true).
- 비용: 메모리 내 산술 + 최근 송금 카운트 쿼리 1회. 부하 매우 작음.
- 이득: NoOp 보다 점수 분포가 생김. Kibana / Grafana 에 `fds.ai.score` 히스토그램을 띄울 수 있고, 룰만으로 안 보이던 "임계 직전" 거래들이 점수 분포로는 드러나요.

**여기서 부끄러운 자리를 적어두면** — 가중치 0.4 / 0.25 / 0.1 / 0.2 / 0.15 / 0.3 은 전부 사람 직관입니다. 어떤 측정도 안 했어요. "이쯤이 합리적이지 않을까" 의 합산이라 false positive rate / true positive rate 도 못 잽니다. 라벨이 없으니까요.

그럼에도 Phase 1 을 들인 이유는, **점수 분포 자체가 다음 단계 평가의 베이스라인** 이 되기 때문이에요. Phase 2 어댑터를 붙였을 때 점수 분포가 어떻게 바뀌었는지를 비교하려면 비교 대상이 필요합니다.

### Phase 2 — Statistical 어댑터 (코드는 들였고 활성은 안 한 자리)

- 구현: `StatisticalAiScoreAdapter`. 사용자별 송금액의 30일 롤링 log-평균 / log-표준편차로 z-score 계산.
  - 표본 5건 미만이면 cold start 로 보고 0.0 반환.
  - `|z|` 가 클수록 점수 ↑. sigmoid `1 / (1 + exp(-(|z| - 2) * 1.5))` 로 0~1.
- 활성화: `fds.ai.score-provider=statistical`. 지금은 비활성.
- 비용: 매 분석마다 사용자 30일 거래 통계 쿼리 1회. **부하 측정 안 됨.**
- 이득: Heuristic 의 "사용자 패턴 무시" 자리를 메움. 평소 5만원 보내던 사람의 80만원은 |z| 가 커지고, 평소 100만원 보내던 사람의 80만원은 |z| 가 작음.

한계도 같이 적습니다.

- **Cold start**: 표본 5건 미만 사용자는 항상 0.0. 신규 사용자는 모두 점수 0 — fraud 가 신규 가입 직후 일어나는 패턴이라면 잡힐 리가 없어요.
- **단변량**: 금액 한 변수만 봐요. 시간대, 빈도, 수취인 다양성은 안 들어가 있습니다.
- **쿼리 부하**: 매 거래마다 30일 윈도우 통계 쿼리 한 번. transactions 테이블 인덱스 (sender_user_id, status, created_at) 가 있어야 견디는 자리.
- **분포 가정**: log-amount 가 정규에 가깝다는 가정. 실제로 그런지 확인 안 했어요.

### Phase 3 — Isolation Forest / 가벼운 인-프로세스 ML (가설)

- 구현 가설: Smile 같은 JVM 머신러닝 라이브러리로 Isolation Forest 를 인-프로세스 학습.
  - 학습 데이터: 과거 거래의 다변량 특성 벡터 (금액 로그, 시간대 원-핫, 최근 빈도, 수취인 다양성 등).
  - 추론: 인-메모리 모델에 벡터 전달 → anomaly score.
  - 재학습: 주기 배치 (일/주 단위), 모델은 객체로 메모리 적재.
- 활성화 후보: `fds.ai.score-provider=isolation-forest`.
- 비용 (추정, 측정 안 됨):
  - 모델 메모리: 트리 100개 × 샘플 256개 기준 수 MB 안쪽.
  - 추론 latency: P99 5ms 이내 추정. ES kNN 보다 빠를 가능성 높음.
  - 재학습 파이프라인: 학습 데이터 추출 + 검증 + 모델 swap 운영 절차 필요.
- 이득:
  - 다변량. Phase 2 의 단변량 한계 해소.
  - 비지도 학습이라 라벨 없어도 동작 (fraud 라벨이 0 인 우리 상황과 맞음).
  - 외부 의존 없음. 라이브러리 1개 추가.

### Phase 4 — ES dense_vector kNN (가설)

- 구현 가설: ML PoC 문서의 권장 경로. 거래 특성 벡터를 Elasticsearch `dense_vector` 인덱스에 적재, 실시간 거래는 kNN 으로 과거 이상 거래와의 유사도를 점수화.
- 비용 (추정):
  - 임베딩 모델 운영 (가벼운 신경망 또는 외부 임베딩 서비스 호출).
  - ES kNN 쿼리 latency 50ms 안에 들어와야 FDS 전체 200ms 예산에 맞음.
  - 인덱스 크기 증가에 따른 운영 부담 (샤딩, kNN 전용 노드 분리 가능성).
- 이득:
  - 거의 실시간 유사도 검색. "이 거래는 작년 12월의 BLOCKED 케이스와 88% 유사" 식의 설명 가능성.
  - 기존 ELK 인프라 재사용. 별도 벡터 DB 도입 안 함.
  - 점진적 확장: 차원 늘리기, 임베딩 모델 교체, 앵커 벡터 보강이 인덱스 갱신만으로 가능.

## 전환 트리거

ADR-010 과 같은 결을 맞춰서, 각 단계 전환을 **측정 가능한 메트릭** 으로 박아둡니다. 사람 직관이 아니라 데이터로 다음 단계로 갑니다.

| 전환 | 트리거 |
|---|---|
| Phase 1 → 2 | Heuristic 가중치를 두 번 이상 튜닝했는데도 점수 분포가 fraud 의심 거래(룰 hits ≥ 2)와 정상 거래를 의미 있게 구분 못 함. 또는 false positive 비율 측정용 라벨이 일부라도 쌓여서 비교가 가능해졌을 때. |
| Phase 2 → 3 | Statistical 의 다변량 한계가 잡힐 때 — 즉, "금액 단독으로는 평범한데 시간 + 빈도 동시에 어긋난" 케이스가 라벨에서 누적 미탐지로 드러날 때. 또는 30일 윈도우 쿼리가 DB 부하의 무시 못 할 비중을 차지하기 시작할 때. |
| Phase 3 → 4 | Isolation Forest 가 cold start (신규 사용자, 장기 미사용자) 에서 fraud 를 놓치는 비율이 비즈니스 손실로 잡힐 때. 또는 점수 설명 가능성 (왜 이 거래가 이상인가) 요구가 운영/규제 쪽에서 들어올 때. |

지금 시점에서 우리가 가진 데이터로는 **Phase 1 → 2 의 트리거조차 측정 못 합니다.** fraud 라벨이 0 이라서요. 그래서 Phase 2 를 코드만 들이고 활성화는 보류한 거예요 — 라벨이 일부 쌓이는 시점이 트리거가 켜지는 시점입니다.

## Alternatives Considered

선택 안 한 길도 적어둡니다. ADR 의 자리는 "왜 다른 길을 안 갔는지" 가 결정만큼 중요해요.

- **외부 SaaS (Sift, Feedzai, AWS Fraud Detector)**: 빠르게 적용 가능. 다만 (1) 벤더 종속, (2) 거래 데이터를 외부 송출하는 규제 부담, (3) 학습/튜닝 통제권 부재. 사이드 프로젝트 학습 목적과도 안 맞아서 보류.
- **LLM 직접 호출 (GPT-4 등)**: 거래 정보를 프롬프트로 넣고 "이상 여부 판정" 받기. latency (수 초), 비용 (거래당 토큰), 결정 추적성 (같은 입력 다른 출력) 셋 다 FDS 에 안 맞습니다. 보류.
- **Deep Learning 모델 (Autoencoder, VAE) 직접 학습**: 표현력은 좋은데 (1) 학습 인프라 (GPU 또는 SageMaker 같은 관리형), (2) 라벨 데이터, (3) 재학습 파이프라인 — 우리 상황에 들이는 게 과해요. Phase 3 의 Isolation Forest 가 비지도 + 인-프로세스 라서 같은 자리에서 비용이 훨씬 작습니다.
- **처음부터 Phase 4 (ES kNN)**: 라벨 없는 상태에서 앵커 벡터 구성이 안 됩니다. Phase 1~3 을 통해 점수 분포와 fraud 라벨이 조금이라도 쌓인 다음에 가야 의미 있어요.

## 한계 / 미검증 자리

ADR 의 "결정" 자리에 안 적었지만, 솔직하게 짚고 넘어가야 하는 자리들입니다.

- **가중치 모두 사람 직관**: `HeuristicAiScoreAdapter` 의 0.4 / 0.25 / 0.1 / 0.2 / 0.15 / 0.3 과 sigmoid 의 `(raw - 0.5) * 4`, `StatisticalAiScoreAdapter` 의 `(|z| - 2) * 1.5` 까지 — 어떤 데이터로도 정당화 안 됐어요. "이쯤이면 0~1 분포에 들어오겠지" 의 직관 합산.
- **라벨 데이터 0**: 어느 어댑터도 precision / recall / F1 측정 안 됩니다. 측정의 출발선이 없는 상태.
- **`aiScore` 와 `decisionWeight` 분리**: 현재 `AnalyzeTransferService` 는 `aiScore` 를 `RiskLog` 에만 기록하고, 최종 결정 (`determineDecision`) 에는 룰 기반 `decisionWeight` 만 씁니다. 즉 지금 aiScore 는 **관찰용 신호**예요. 점수가 0.9 가 떠도 거래는 차단 안 됩니다. 이걸 어느 시점에 결정 로직에 결합할지가 별도 ADR 자리.
- **부하 측정 안 됨**: Statistical 의 30일 윈도우 쿼리가 매 거래마다 발생하는데, transactions 테이블이 커진 상황에서의 실제 latency / DB CPU 영향은 안 잡혔습니다. 활성화 전에 측정 필요.
- **분포 가정**: Statistical 어댑터가 log-amount 의 정규성을 가정하는데, 실제 분포 형태를 안 봤어요. 두꺼운 꼬리거나 다봉 (multimodal) 이면 z-score 의 의미가 약해집니다.

## References

- 코드:
  - `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/required/AiScoreProvider.kt` — 포트
  - `services/fds/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/container/adapter/NoOpAiScoreProvider.kt` — Phase 0
  - `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/ai/HeuristicAiScoreAdapter.kt` — Phase 1
  - `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/ai/StatisticalAiScoreAdapter.kt` — Phase 2
  - `services/fds/application/src/main/kotlin/io/github/hyungkishin/transentia/application/service/AnalyzeTransferService.kt` — 어댑터 호출부
- 관련 문서:
  - [ml-anomaly-detection-poc.md](../etc/ml-anomaly-detection-poc.md) — Phase 4 의 상세 설계 (ES kNN)
  - [ADR-010](ADR-010-async-event-publishing-evolution.md) — 같은 "단계별 진화 + 전환 트리거" 형식의 선례
  - [overengineering-check.md](../retrospective/overengineering-check.md) — ADR 형식 과잉에 대한 회고 (이 ADR 도 Phase 2~4 가 "결정한 것" 이 아니라 "지도" 임을 의식하고 작성)
- 외부:
  - [Liu et al., Isolation Forest (2008)](https://cs.nju.edu.cn/zhouzh/zhouzh.files/publication/icdm08b.pdf) — Phase 3 의 알고리즘 근거
  - [Elasticsearch — kNN search](https://www.elastic.co/guide/en/elasticsearch/reference/current/knn-search.html) — Phase 4 의 인프라
  - [Smile — Statistical Machine Intelligence and Learning Engine](https://haifengl.github.io/) — Phase 3 의 후보 라이브러리
