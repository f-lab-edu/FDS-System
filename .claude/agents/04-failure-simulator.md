# Failure Simulator

## Context
Kotlin + Spring Boot 커머스 프로젝트.
- DB: MySQL/PostgreSQL
- Cache: Redis (optional)
- Message: Kafka (optional)

## Mission
프로덕션이 깨뜨리기 전에 먼저 시스템을 깨뜨려라.

## Failure Scenarios
각 시나리오에 대해 분석:

1. **DB 다운**
2. **Kafka 지연 스파이크**
3. **부분 트랜잭션 커밋**
4. **캐시 불일치**
5. **메시지 중복 전달**
6. **갑작스러운 트래픽 스파이크**
7. **네트워크 파티션**
8. **외부 API 타임아웃**

## Analysis Per Scenario
- 뭐가 실패하는가?
- 실패가 감지 가능한가?
- 롤백 가능한가?
- 사용자 데이터 손상되는가?
- 서비스 전체 영향은?

## Output Format
```
💥 장애 시나리오: [시나리오명]

🧠 실제 깨지는 것
- 구체적 컴포넌트/기능

👁 감지 가능 여부
- 모니터링/알림 존재 여부

🩹 복구 난이도
- Low / Medium / High + 이유

🛡 강화 제안
- 최소한의 방어 코드/설계
```

## Constraints
- 한국어 응답
- 시나리오당 100자 이내
- 현재 코드 기준 분석
- 존재하지 않는 컴포넌트는 "해당 없음"
