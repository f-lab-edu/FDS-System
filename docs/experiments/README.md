# Experiments — 실험 기반 의사결정 기록

> 이 디렉터리는 시스템의 굵직한 결정들을 **가설 → 실험 설계 → 측정 → 회고** 사이클로 추적한다.
> 직관/관습이 아닌 데이터가 결정의 근거. ADR-009 가 메타 규칙.

## 실험 인덱스

| # | 제목 | Status | 핵심 가설 | 결과 | ADR 연결 |
|---|---|---|---|---|---|
| [E1](E1-outbox-partitioning/) | Outbox MOD 파티셔닝 | ✅ RUN | DB 쿼리 ≥ 90% 감소 | H1 ✓ (99%) / H2 ✓ / H3 ✗ (12.7% 증가) | [ADR-003](../adr/ADR-003-relay-partitioning-by-modulo.md) |
| [E2](E2-locking-strategy/) | 낙관락 → 비관락 | ✅ RUN | 부하 시 에러율 50%p 감소 | H1 ✓ (62%p) / H2 ✓ (latency 2배 — 감수) / H3 ✓ (데드락 0) | [ADR-002](../adr/ADR-002-transactional-outbox-pattern.md) |
| [E3](E3-circuit-breaker/) | Redis CB chaos | ⏸ NOT YET RUN | CB 적용 시 p95 < 200ms 유지 | 추정 / chaos 도구 부재 | [ADR-005](../adr/ADR-005-redis-daily-limit-cache.md) |
| [E4](E4-load-baseline/) | k6 부하 베이스라인 | ✅ RUN (단발) | VU=10 선형, 50+ sublinear | 단일 인스턴스 한계 측정 | [DECISIONS-TIMELINE Stage 6](../DECISIONS-TIMELINE.md) |

## 실험 방법론

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐     ┌────────────┐
│  Hypothesis │ ──→ │   DESIGN.md  │ ──→ │  실행 + 측정  │ ──→ │ RESULTS.md │
└─────────────┘     └──────────────┘     └──────────────┘     └────────────┘
                                                                     │
                                                                     ▼
                                          ┌──────────────────────────────┐
                                          │ RETRO.md + 후속 실험 가설 도출 │
                                          └──────────────────────────────┘
                                                     │
                                                     ▼
                                              새 실험 E(N+1)
```

### 4종 문서 표준

| 문서 | 목적 | 작성 시점 |
|---|---|---|
| `DESIGN.md` | 가설/변수/측정 방법/성공 기준 | **측정 이전에 작성 (사후 합리화 방지)** |
| `RESULTS.md` | 실측 데이터/가설 검증/트레이드오프 정량화 | 측정 완료 후 |
| `RETRO.md` | 회고/한계 인정/후속 실험 후보 | RESULTS 후 |
| `diagrams/*.mmd` → `*.svg` | 실험 흐름/결과 시각화 | 마지막 |

### 작성 규칙 (ADR-009 인용)

1. **가설 먼저, 측정 나중**. DESIGN.md 가 git history 상 RESULTS.md 보다 먼저.
2. **실패 인정**. 가설이 틀렸으면 RESULTS.md 에 명시. 사후 합리화 금지.
3. **미수행 ≠ 무시**. 실험을 안 했으면 Status: NOT YET RUN + RETRO 에 갭 인정.
4. **트레이드오프 정량화**. "더 좋다" → "비용 X 증가, 이득 Y 증가".

## 후속 실험 후보 (미작성)

| ID | 제목 | 트리거 |
|---|---|---|
| E5 | 멀티 인스턴스 부하 비교 (2~5대 transfer-api) | E4 의 단일 인스턴스 천장 확인 |
| E6 | Kafka Streams 처리량 한계 (단일 vs 듀얼 파이프라인) | Phase 3 회고 |
| E7 | ES dense_vector kNN PoC (AiScoreProvider 실 구현) | docs/etc/ml-anomaly-detection-poc.md |
| E8 | Toxiproxy 기반 chaos 매트릭스 (E3 실측) | E3 의 NOT YET RUN 해소 |
| E9 | Redis fail-open vs fail-closed 비교 | E3 후속 |
| E10 | Outbox archive 도입 시 디스크/쿼리 영향 | LIMITATIONS 2.1 |

## 관련 문서

- [ADR-009](../adr/ADR-009-experiment-driven-decisions.md) — 메타 방법론
- [DECISIONS-TIMELINE](../DECISIONS-TIMELINE.md) — 실험과 결정의 시간 축
- [LIMITATIONS](../LIMITATIONS.md) — 실험 미수행 영역
- [load-test/](../load-test/) — k6 시나리오 코드
- [retrospective/](../retrospective/) — Phase 별 회고 (실험 결과 종합)
