# E6 — AiScoreProvider 진화 실험 설계서

> **Status**: 설계 단계. 실험 미수행. 측정값은 본 문서에 포함되지 않는다.
> **목적**: Phase 4 에 들어간 두 AiScoreProvider 어댑터 (Heuristic / Statistical) 가
> 의도대로 동작하는지를 **분포 수준에서** 검증한다.
> **선행 코드**: `HeuristicAiScoreAdapter`, `StatisticalAiScoreAdapter` (Phase 4 PR).
> **선행 회고**: [phase-4-ml-evolution.md](../../retrospective/phase-4-ml-evolution.md)

---

## 1. 배경

### 1.1 왜 이 실험이 필요한가

Phase 4 에서 두 어댑터를 코드에 들였는데, 둘 다 측정 없이 들였다.
[ADR-009] 가 "직관/관습이 아닌 데이터가 결정의 근거" 라고 적고 있지만, Phase 4 의 두 어댑터는 가중치도 임계값도 sigmoid scale 도 전부 직관이다.

이 실험이 메우려는 자리는 두 곳이다.

1. **분포 수준 검증** — Heuristic 적용 시 score 분포가 룰 만으로는 안 보이던 의심 자리를 만들어 내는가.
2. **운영 영향 측정** — 두 어댑터 도입이 송금 분석 처리량에 의미 있는 영향을 주는가.

라벨이 없으니 정밀도/재현율은 못 잰다. 그건 다음 라운드 (E6-1) 의 자리다.

### 1.2 두 어댑터 요약

| 어댑터 | 입력 | 변수 | 한계 |
|---|---|---|---|
| `HeuristicAiScoreAdapter` | 단일 이벤트 + 최근 5분 송금 카운트 | 금액 / 시간대 / 속도 가중합 → sigmoid | 가중치 직관, 학습 없음 |
| `StatisticalAiScoreAdapter` | 단일 이벤트 + 30일 사용자 송금 통계 | log 금액의 z-score → sigmoid | 다변량 아님 (금액만), cold start 시 0.0 |

두 어댑터는 `fds.ai.score-provider` 프로퍼티로 한 번에 하나만 활성된다.
`heuristic` (default) / `statistical` / `noop` 셋 중 하나.

### 1.3 메트릭 — fds.ai.score Distribution

두 어댑터 모두 동일 이름의 `DistributionSummary` 를 등록한다.

```kotlin
DistributionSummary.builder("fds.ai.score")
    .description("...")
    .baseUnit("score")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(meterRegistry)
```

Prometheus 에서 `fds_ai_score_*` 시리즈로 노출되고, 어댑터별 분포 비교의 1차 데이터다.

---

## 2. 가설

각 가설은 **시나리오 + 측정 지표 + 기대 임계값** 의 묶음이다.

### H1 — Heuristic 이 의심 자리를 만들어 낸다

> Heuristic 적용 시 `fds.ai.score >= 0.7` 인 거래 비율이 전체의 **5% 이상**이다.

근거.
- 룰 엔진 (`AnalyzeTransferService`) 의 단건 룰은 `HIGH_AMOUNT`, `SINGLE_HIGH_AMOUNT`, `RAPID_TRANSFER` 셋이다.
- 룰은 binary 다. 룰을 위반하지 않은 거래는 weight 가 0 이고 `ALLOWED` 로 간다.
- Heuristic 은 룰 위반이 아니어도 새벽 시간 / 큰 금액 / 약간 빈번한 거래에 점진적 score 를 준다. 즉 룰 binary 가 놓치는 그라데이션 자리가 보여야 한다.

측정.
- k6 시나리오 `load-test/scripts/e6-heuristic-baseline.js` (작성 예정) 로 10분간 1000건 송금 발생.
- 시나리오 안에 인위적으로 "새벽 + 100만원 + 짧은 간격 3건" 패턴을 5% 비율로 섞는다.
- Prometheus 쿼리: `histogram_quantile(0.95, fds_ai_score_bucket)`.

성공 기준.
- `fds.ai.score >= 0.7` 비율이 5% 이상이면 H1 ✓.
- 5% 미만이면 H1 ✗ — 가중치가 너무 낮거나 sigmoid scale 이 압축을 너무 강하게 한다는 신호.

### H2 — Statistical 의 cold start 가 안전하다

> Statistical 적용 시 신규 사용자(거래 < 5건) 의 score 평균이 기존 사용자 score 평균과 **0.1 이내** 차이.

근거.
- Statistical 은 `count < MIN_SAMPLES` 면 0.0 을 반환한다. 즉 신규 사용자의 score 는 항상 0.0.
- 기존 사용자의 score 분포 평균이 0.1 ~ 0.3 자리에 있을 거라 예상한다 (대부분 거래가 평소 패턴 안에 있음).
- 두 그룹 평균 차이가 0.1 이내라면 cold start 처리가 "전체 분포를 왜곡하지 않는다" 는 의미다. 차이가 크면 신규 사용자가 분포의 왼쪽 끝으로 몰려 "신규 = 무조건 안전" 신호가 운영자에게 잘못 박힐 위험이 있다.

측정.
- k6 시나리오에서 신규 user_id 와 기존 user_id 를 50:50 으로 섞는다.
- Prometheus 라벨링으로 두 그룹 score 분포를 분리해 본다 (label `user_cohort=new|existing`).
- 평균 차이 = `avg(fds_ai_score{user_cohort="existing"}) - avg(fds_ai_score{user_cohort="new"})`.

성공 기준.
- 차이 ≤ 0.1 → H2 ✓.
- 차이 > 0.1 → H2 ✗ — cold start 정책을 재검토할 자리. Heuristic fallback 또는 신규 사용자 별도 threshold 도입.

### H3 — 어댑터 도입의 throughput 영향 ≤ 5%

> Heuristic / Statistical 둘 다, NoOp 대비 분석 처리량 감소가 **5% 이하**.

근거.
- Heuristic 은 `recentTransferCountQueryPort.countByUserSince` 한 번 호출. 인덱스 `idx_tx_sender_created` 가 있어서 비용은 단건 ms 자리.
- Statistical 은 30일 롤링 쿼리 한 번 호출. 인덱스 적용 시 사용자당 거래 수가 늘어날수록 비용이 증가하는 자리.
- 5% 는 운영에서 감수 가능한 자리로 잡았다. 그 이상이면 캐싱 또는 비동기 분리가 필요하다는 신호.

측정.
- 동일 k6 시나리오를 `fds.ai.score-provider=noop` / `=heuristic` / `=statistical` 셋으로 각각 돌린다.
- 분석 처리량 = `rate(fds_analyze_transfer_total[1m])`.
- 또는 송금 → RiskLog 영속화까지의 end-to-end p95.

성공 기준.
- 두 어댑터 모두 noop 대비 처리량 감소 ≤ 5% → H3 ✓.
- 한쪽이라도 5% 초과 → H3 부분 ✗ + 어느 쪽에서 비용이 큰지 식별.

### H4 (negative) — 라벨 부재로 정밀도/재현율은 측정 불가

> 본 실험은 **분포만 본다.** 어느 어댑터가 더 정확한가는 본 실험으로 답할 수 없다.

근거.
- `transactions` / `risk_logs` 어느 쪽에도 `is_fraud` 운영자 라벨 컬럼이 없다.
- 라벨 없는 상태에서 score 와 실제 fraud 의 일치도를 잴 길이 없다. confusion matrix 자체가 안 만들어진다.
- "score 가 0.8 이상인 거래를 사람이 봤더니 진짜 fraud 였다" 는 사후 라벨링은 운영자 검토 인터페이스가 있어야 가능한 자리인데, 그 인터페이스가 아직 없다.

이 가설은 검증할 게 아니라 **인정할 가설**이다.
E6 가 끝난 다음 라운드 — 운영자 검토 UI + 라벨 컬럼 + 라벨 1000건 축적 — 후의 E6-1 자리.

---

## 3. 측정 변수

| 변수 | 출처 | 단위 | 용도 |
|---|---|---|---|
| `fds.ai.score` 분포 | Prometheus DistributionSummary | 0.0~1.0 | H1, H2 |
| `fds.ai.score{quantile=0.95}` | Prometheus | 0.0~1.0 | H1 임계값 |
| `fds_analyze_transfer_seconds` p95 | Prometheus Histogram (분석 latency) | ms | H3 |
| `rate(fds_analyze_transfer_total[1m])` | Prometheus | req/s | H3 |
| 사용자 코호트 라벨 (`user_cohort`) | k6 시나리오에서 부여 | new / existing | H2 |
| 어댑터 식별 (`fds.ai.score-provider`) | application property | heuristic / statistical / noop | 실험 분기 |

---

## 4. 도구 / 환경

- **k6** — `load-test/scripts/e6-*.js` (작성 예정). 시나리오 3종.
- **Prometheus** — `services/fds/instances/api` 의 `/actuator/prometheus` 스크랩.
- **Grafana** — 분포 시각화. `fds.ai.score` 히스토그램 panel.
- **로컬 docker-compose 환경** — `docker-compose.yml` 의 fds-api + postgres + redis + kafka. 외부 부하 없음 (단일 노드).

부하 규모.
- k6 VU 10, 10분, 약 1000~3000 송금 (Phase 2 의 E4 baseline 과 동일 자리).
- 단일 인스턴스 천장은 [E4](../E4-load-baseline/) 에서 확인된 자리. 그 천장 안에서 어댑터 영향만 본다.

---

## 5. 실행 절차 (예정)

1. 코드 freeze: HeuristicAiScoreAdapter, StatisticalAiScoreAdapter 변경 금지.
2. 데이터 seed: 신규/기존 user 50:50 비율로 사용자 100명 생성. 기존 사용자에게는 30일치 거래 5~50건 분포로 seed.
3. NoOp baseline 실행 — `fds.ai.score-provider=noop` 로 10분.
4. Heuristic 실행 — `=heuristic` 로 10분. 동일 k6 시나리오.
5. Statistical 실행 — `=statistical` 로 10분. 동일 k6 시나리오.
6. Prometheus 쿼리로 H1/H2/H3 평가.
7. RESULTS.md 작성. 가설별 ✓/✗ + 근거 수치.

---

## 6. 실험을 통해 답하지 않는 것

이 자리를 미리 박아둔다. 사후 합리화 방지.

- "Heuristic 과 Statistical 중 어느 게 더 좋은가" — 라벨 없이는 답 못 함.
- "score 임계치를 얼마로 잡아야 BLOCKED 인가" — 분포만 본다. 정책은 별도 의사결정.
- "외부 ML SaaS 가 더 나은가" — 본 실험 범위 밖. Phase 5 의 자리.
- "Isolation Forest / dense_vector kNN 이 더 나은가" — E7 의 자리.

---

## 관련

- [phase-4-ml-evolution.md](../../retrospective/phase-4-ml-evolution.md)
- [ADR-009 실험 주도 의사결정](../../adr/ADR-009-experiment-driven-decisions.md)
- ADR-011 AiScoreProvider 진화 전략 (예정)
- [E4 부하 베이스라인](../E4-load-baseline/) — H3 의 환경 기준
- `docs/etc/ml-anomaly-detection-poc.md` — E7 후보
