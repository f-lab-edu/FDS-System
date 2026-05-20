# ADR-009: 실험 주도 의사결정 (Experiment-driven Decisions)

- **Status**: Accepted
- **Date**: 2026-05-19
- **Tags**: methodology, experimentation, observability, decision-making

---

## Context

이 프로젝트의 핵심 결정들(낙관락 → 비관락, 단순 SKIP LOCKED → MOD 파티셔닝, Redis 도입, CB 적용 등)이 **직관/관습이 아니라 측정에 기반**한다는 사실을 ADR/회고로 흩어 적었지만, **실험 사이클 자체가 표준화되어 있지 않았다**.

증상:

1. "왜 비관락으로 회귀했나" 같은 질문을 받으면 commit 메시지 + 회고 글을 뒤져야 함.
2. 실험 데이터(k6 로그)와 ADR 의 결정문이 따로 놀음.
3. 후속 실험(예: "멀티 인스턴스 부하")이 어디서 어떻게 진행되는지 합의된 위치 없음.
4. 측정 안 한 결정(예: Redis fail-open 정책)이 ADR 에는 "결정" 으로 적혔지만 실험 부재가 명시적이지 않음.

## Decision

`docs/experiments/EN-{slug}/` 디렉터리 단위로 **실험 사이클 4종 문서**를 표준화한다.

```
docs/experiments/
├── README.md                       # 실험 인덱스 + 방법론
├── E1-outbox-partitioning/
│   ├── DESIGN.md                   # 가설 + 변수 + 측정 방법 + 성공 기준
│   ├── RESULTS.md                  # 실측 데이터 + 가설 검증 + 트레이드오프
│   ├── RETRO.md                    # 회고 + 후속 실험 후보
│   └── diagrams/*.mmd → svg
├── E2-locking-strategy/
├── E3-circuit-breaker/             # 실험 미수행 = 갭 명시
└── E4-load-baseline/
```

**규칙**:

1. **가설 먼저, 측정 나중**. DESIGN.md 가 작성된 후에만 측정 진행.
2. **실패 인정**. 가설이 틀렸으면 RESULTS.md 에 명시. 사후 합리화 금지.
3. **미수행 ≠ 무시**. 실험을 안 했으면 RESULTS.md 의 Status 에 "NOT YET RUN" 표기 + RETRO 에 갭 인정.
4. **트레이드오프 정량화**. "더 좋다" 가 아니라 "비용 X 증가, 이득 Y 증가".
5. **ADR 와 실험의 연결**. 모든 주요 ADR 은 관련 실험 디렉터리를 참조해야 한다.

## Consequences

### Positive

- 결정의 근거를 누구든 같은 위치(`docs/experiments/EN-*/`)에서 찾는다.
- 가설 → 측정 → 회고 순서가 "데이터를 보고 가설을 만드는" 사후 합리화를 차단.
- 미수행 실험이 표준 양식으로 노출 → 후속 PR 의 입력.
- 빅테크 운영의 실험 표준(예: Netflix Tech Blog, Uber Engineering)과 정합.

### Negative

- 작은 결정에도 4종 문서 작성 → 오버헤드.
- 가설 작성이 익숙하지 않은 합류자에겐 학습 곡선.
- 실험 미수행이 정직히 드러나면 "왜 안 했냐" 추궁받기 쉬움 — 정직성의 비용.

### Neutral

- 결정 사이즈에 비례한 실험 깊이가 필요 (큰 결정은 풀 사이클, 작은 결정은 간략).
- ADR 과 실험 디렉터리의 cross-reference 유지보수 필요.

## Alternatives Considered

| 대안 | 장점 | 단점 | 채택 여부 |
|---|---|---|---|
| **ADR 단일 문서에 측정 데이터 포함** | 한 곳에서 끝 | ADR 가 너무 길어짐, 측정 갱신 시 ADR 흔들림 | ✗ |
| **회고 글에만 측정 기록** | 작성 부담 적음 | 가설/방법 분리 안 됨, 사후 합리화 위험 | ✗ |
| **Notion / Confluence 외부 위키** | 도구 친화 | 코드 레포와 분리, PR 리뷰 부재 | ✗ |
| **별도 experiments 디렉터리 + 4종 표준** | 코드 레포 안에 자급, 표준화 | 오버헤드, 학습 곡선 | ✓ |

## Trade-offs

- **표준화 vs 가벼움**: 표준 4종 문서가 모든 결정에 과한 경우(예: TODO 주석 제거) 있음. 가벼운 결정은 ADR 만, 큰 결정은 풀 사이클.
- **정직성 vs 인상**: 미수행 실험이 정직히 드러나는 게 시니어 레벨이지만, 외부 평가자에겐 "부족해 보임". 후속 PR 의 입력으로 활용한다는 명시가 필요.

## Implementation Notes

각 실험 디렉터리의 표준 템플릿:

- **DESIGN.md**:
  ```
  # E{N} 실험 설계 — {제목}
  ## 배경 / Hypothesis (H1, H2, H3...) / 변수(독립/종속/통제) /
  ## 측정 방법 / 실험 조건 / 성공 기준 / 실패 기준 / 미정 변수
  ```

- **RESULTS.md**:
  ```
  ## Status: RUN / NOT YET RUN
  ## 실측 데이터 표 / 가설 검증 표(H1 ✓ / H2 ✗ ...) /
  ## 의외 발견 / 트레이드오프 정량화 / 한계
  ```

- **RETRO.md**:
  ```
  ## 가설이 맞았던 부분 / 틀렸던 부분 / 측정 안 한 변수 /
  ## 후속 실험 후보 / 운영 적용 시 주의
  ```

- **diagrams/*.mmd** → SVG (mmdc 로 빌드).

## References

- 실험 인덱스: [`docs/experiments/README.md`](../experiments/README.md)
- 메타 회고: 추후 분기별 실험 회고록 작성 예정.
- 관련 ADR: 모든 ADR 이 실험과 연결됨 (cross-reference).
- 외부:
  - [Netflix Tech Blog — Chaos Engineering](https://netflixtechblog.com/)
  - [Uber Engineering — Experimentation](https://eng.uber.com/)
  - Maxime Beauchemin, "The Rise of the Data Engineer" 패러다임.
