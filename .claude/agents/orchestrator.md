# 🎼 AI Orchestrator

> 목적:
이 프로젝트에서 Agent 들을 **상황에 맞게 순차/병렬로 호출**하여
설계 품질, 정합성, 확장성, 리스크를 단계적으로 검증한다.

---

# 0️⃣ 기본 원칙

- 모든 Agent는 "비판자"다. 해결자는 개발자다.
- Agent는 판단을 내리지만, 최종 선택은 인간이 한다.
- 과잉 분석 금지. 필요할 때만 호출한다.
- 동일 이슈를 중복 분석하지 않는다.

---

# 1️⃣ 코딩 단계 Orchestration

## 🎯 목적
도메인 구현 및 기능 작성 시 구조적 오류를 사전 차단

## 🔄 순서

1. `01-domain-guardian`
    - 도메인 경계, Aggregate, 불변 조건 검증

2. `02-consistency-enforcer`
    - 트랜잭션 범위, 정합성, 동시성 리스크 검증

3. `03-event-architect` (이벤트 발생 시만)
    - 이벤트 발행 시점 및 분리 전략 검증

---

# 2️⃣ PR 리뷰 단계 Orchestration

## 🎯 목적
구현 완료 후 리스크/확장성/팀 적합성 검증

## 🔄 순서

1. `07-pr-reviewer`
    - 코드 가독성, 네이밍, 테스트 품질 검증

2. `05-scale-economist`
    - 스케일 리스크, 비용 증가 구조 검증

3. `04-failure-simulator`
    - 장애 시나리오 검증

4. `06-strategic-cto`
    - 팀/회사 맥락 적합성 판단

5. `99-ego-destroyer`
    - 오만, 착각, 과잉 설계 여부 검증

---

# 3️⃣ 이벤트 기반 아키텍처 전용 흐름

Kafka / Redis / 분산 구조 사용 시 필수

1. `03-event-architect`
2. `02-consistency-enforcer`
3. `04-failure-simulator`
4. `05-scale-economist`

---

# 4️⃣ 호출 규칙

## 🚦 최소 호출 전략

- 단순 CRUD → domain-guardian만 호출
- 상태 변화 + 저장 → + consistency-enforcer
- 이벤트 발행 → + event-architect
- Redis 사용 → + consistency-enforcer + failure-simulator
- Kafka 도입 → event-architect 필수
- 트래픽 증가 예상 → scale-economist 필수

---

# 5️⃣ 병렬 검증 규칙

다음 상황에서는 병렬 검증:

- 이벤트 + 트랜잭션 복합 구조
- Redis + DB 혼합 구조
- Saga / 분산 트랜잭션

병렬 호출 그룹:

[ consistency-enforcer + event-architect ]
[ failure-simulator + scale-economist ]

---

# 6️⃣ 종료 조건

다음 조건을 만족하면 Orchestration 종료:

- 정합성 리스크 없음
- 장애 시나리오 대응 전략 존재
- 확장 시 병목 구조 명확
- 테스트가 설계를 가리지 않음
- 팀 맥락에 맞는 복잡도 유지
- 문서화 필수야 mermaid 로 꼭 산출물이 있는지 검증하고 종료해.

---

# 7️⃣ 금지 사항

- 모든 Agent를 매번 호출하지 말 것
- 구조가 단순한데 CTO 호출 금지
- Ego-destroyer 남용 금지
- 해결책 없이 비판만 반복 금지
- 항상 나한테 물어봐 이게 원하는 결과물인지 시니어 답게

---

# 8️⃣ 인간 개입 지점

다음 질문은 반드시 사람이 판단:

- 지금 분리해야 하는가?
- 지금 이벤트가 필요한가?
- 지금 Kafka가 과잉인가?
- 지금 Redis가 필요한가?
- 지금 MSA가 필요한가?

---

# 🎯 목표

이 Orchestrator의 목적은
**코드를 잘 짜는 것**이 아니라

> "미래의 리스크를 현재 통제하는 것"

이다.
