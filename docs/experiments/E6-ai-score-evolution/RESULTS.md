# E6 — AiScoreProvider 진화 실험 결과

> **Status**: NOT YET RUN.
> k6 시나리오와 user seed 가 미작성. 본 문서는 가설 기반 예상 분포와
> 실험 없이도 부분 검증 가능한 자리만 담는다.

---

## 1. 현재 시점에 측정된 것

없음. 본 실험은 아직 실행되지 않았다.

`fds.ai.score` Distribution metric 은 코드에 박혀 있고, Prometheus 노출도 확인됐다.
다만 부하를 의도된 분포로 흘려본 적이 없어서 분포 데이터가 없다.

운영 환경 (단일 인스턴스) 에서 자연 발생한 거래는 수십 건 자리. 통계적 의미를 가질 모수가 안 된다.

---

## 2. 가설별 예상 분포 (실측 아님)

다음은 코드 로직을 손으로 추적해서 잡은 예상 분포다. 실측치가 아니다.

### H1 예상 — Heuristic 분포

k6 시나리오에 "새벽 + 100만원 + 짧은 간격 3건" 패턴을 5% 비율로 섞었을 때 예상.

| 거래 유형 | 예상 비율 | 예상 score 구간 |
|---|---|---|
| 평범한 거래 (낮 시간, 10만원 이하, 빈도 낮음) | 60% | 0.1 ~ 0.3 |
| 약간 큰 금액 (100만원~) 낮 시간 | 25% | 0.3 ~ 0.5 |
| 새벽 시간 거래 (금액 무관) | 10% | 0.4 ~ 0.6 |
| 새벽 + 큰 금액 + 빠른 빈도 (의심 패턴) | 5% | 0.7 ~ 0.95 |

근거: HeuristicAiScoreAdapter 의 가중치 합산을 모든 경우에 대입.
- amountFeature: 0 ~ 0.4
- hourFeature: 0 또는 0.2
- velocityFeature: 0 ~ 0.3
- 합 raw = 0 ~ 0.9
- sigmoid `1 / (1 + exp(-(raw - 0.5) * 4))` 적용 시 raw=0.5 가 score 0.5 의 중심.

H1 ✓ 예상 — 5% 자리가 0.7 이상으로 들어갈 것으로 본다.
다만 실측 전엔 sigmoid scale (`* 4`) 이 너무 압축적이라 0.7 자리에 도달 못 할 가능성도 있다. 실측 필요.

### H2 예상 — Statistical cold start

- 신규 사용자 score: 무조건 0.0 (cold start 분기).
- 기존 사용자 score 평균: 0.05 ~ 0.15 자리로 예상. 대부분 거래가 z-score 1.0 이하 자리에서 일어나기 때문.

H2 ✓ 예상 — 차이가 0.05 ~ 0.15 자리. 0.1 임계 근처라 결과가 갈릴 수 있다.

만약 기존 사용자 평균이 0.2 이상이면 H2 ✗ — sigmoid scale `(zAbs - 2.0) * 1.5` 가 너무 민감하다는 신호.

### H3 예상 — Throughput 영향

- NoOp: 분석 처리량의 기준점.
- Heuristic: `recentTransferCountQueryPort` 한 번 호출. 인덱스 사용. 예상 영향 1~3% 감소.
- Statistical: 30일 롤링 쿼리 한 번 호출. AVG / STDDEV 집계. 예상 영향 3~8% 감소.

H3 부분 ✗ 예상 — Statistical 이 5% 를 넘길 가능성이 있다. 사용자별 30일 거래 수가 많을수록 비용이 늘기 때문.
넘기면 RETRO 에 "Statistical 어댑터는 캐싱 또는 비동기 분리가 필요" 로 적어야 한다.

### H4 — 측정 불가 (인정)

라벨 부재로 정밀도/재현율은 본 실험에서 측정할 수 없다.
이 항목은 RESULTS 단계에서 검증될 자리가 아니라 RETRO 에서 후속 실험 (E6-1) 의 트리거로 들어간다.

---

## 3. 실험 없이도 부분 검증 가능한 자리

`HeuristicAiScoreAdapter` 의 일부 동작은 단위 테스트로 확인 가능하다.

| 확인 가능 항목 | 검증 방법 | 현재 상태 |
|---|---|---|
| amountFeature 가 구간별 정확한 값 반환 | 단위 테스트 (`HeuristicAiScoreAdapterTest`) | 미작성 |
| hourFeature 가 02~05 KST 에 0.2 반환 | 단위 테스트 + 타임존 픽스처 | 미작성 |
| velocityFeature 가 count 임계 (3, 5) 에서 단계 변경 | mock RecentTransferCountQueryPort | 미작성 |
| sigmoid 출력이 [0, 1] 안에 머무름 | 경계 케이스 (raw=0, raw=1.0) | 미작성 |
| cold start 시 Statistical 이 0.0 반환 | mock JdbcTemplate | 미작성 |

이 단위 테스트들은 가설 H1 / H2 의 부분 검증에는 못 미치지만 (전체 분포가 아닌 단일 case), 어댑터 동작의 sanity check 로는 충분하다.
실측 전에라도 이 자리부터 메우는 게 옳다.

---

## 4. 미실행 사유

세 가지가 동시에 사실이다.

1. **k6 시나리오 미작성.** 신규/기존 user 코호트 분리 + Prometheus 라벨링 + 의심 패턴 5% 주입 시나리오가 없다.
2. **user seed 데이터 미작성.** 30일치 거래 5~50건 분포로 사용자 100명을 만드는 fixture 가 없다. Statistical 어댑터 검증의 전제.
3. **운영 일정 우선순위.** Phase 5 (외부 ML 또는 라벨 수집 인터페이스) 가 더 가치 있는 자리로 보여서, E6 측정에 자리를 비우지 못했다.

---

## 5. 미실행 자체의 비용

E6 가 안 돌면 다음이 안 풀린다.

- Heuristic 의 가중치가 실제로 분포에 어떤 영향을 주는지 모른다. Phase 4 회고의 "직관 가중치" 인정이 그대로 남는다.
- Statistical 의 30일 롤링 쿼리 비용을 모른다. 운영 환경에서 어느 한계까지 견디는지 모른다.
- 두 어댑터 중 어느 쪽이 default 여야 하는지 결정 근거가 없다. 현재 default 가 Heuristic 인 건 "단순하니까" 라는 직관 자리.

ADR-011 의 약점이 그대로 남는 상태.

---

## 6. 다음 액션

우선순위 순서.

1. `HeuristicAiScoreAdapterTest`, `StatisticalAiScoreAdapterTest` 단위 테스트 작성 (실험 없이 가능한 자리).
2. user seed fixture 작성 (30일치 거래 분포).
3. k6 시나리오 `e6-heuristic-baseline.js`, `e6-statistical-baseline.js`, `e6-noop-baseline.js` 작성.
4. 로컬 docker-compose 환경에서 3종 시나리오 실행.
5. 본 RESULTS.md 를 실측 데이터로 교체. 가설별 ✓/✗ 표기.
6. RETRO.md 갱신.

---

## 관련

- [DESIGN.md](./DESIGN.md)
- [RETRO.md](./RETRO.md)
- [phase-4-ml-evolution.md](../../retrospective/phase-4-ml-evolution.md)
