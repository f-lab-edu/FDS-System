# 멀티모듈 설계 V1

## 디렉토리 구조 및 모듈 책임

```text
fds-system/
├── api-server        # 외부 요청을 받는 Spring Boot API
├── core-transfer     # 송금 도메인 로직 (Service, TxPolicy 등)
├── core-risk         # 이상 거래 탐지 도메인 (Rule, Eval 등)
├── infra-kafka       # Kafka 설정 및 Consumer, Producer
├── infra-redis       # Redis 관련 Repository 및 Lua 모듈
├── infra-rdb         # JPA Entity + Repository
├── common            # DTO, 공통 유틸, 공통 예외 등
├── build.gradle.kts
└── settings.gradle.kts
```

## 모듈간 의존성
```text
common       <-- 모든 모듈에서 참조
core-transfer  <-- api-server, txworker
core-risk      <-- detector
infra-redis    <-- txworker, detector, syncworker
infra-kafka    <-- 모든 이벤트 기반 모듈
infra-rdb      <-- txworker, syncworker, detector

api-server
├── dependsOn: core-transfer, infra-*, common
├── exposes: REST (login, transfer)

auth
├── 독립 모듈 (JWT 발급만 담당)

txworker
├── dependsOn: core-transfer, infra-redis, infra-kafka, infra-rdb

detector
├── dependsOn: core-risk, infra-redis, infra-kafka, infra-rdb

syncworker
├── dependsOn: infra-kafka, infra-redis, infra-rdb
```

