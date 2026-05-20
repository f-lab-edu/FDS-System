# ADR-001: 헥사고날 아키텍처의 실용주의적 채택

- **Status**: Accepted
- **Date**: 2026-01-15
- **Deciders**: Backend Lead
- **Tags**: architecture, modules, hexagonal, ddd

---

## Context

Transentia 는 송금(transfer) 도메인과 이상거래탐지(fds) 도메인을 동시에 다루는 시스템이다. 두 도메인은 Kafka 이벤트로 연결되지만 비즈니스 규칙은 완전히 다르다. 송금은 강한 정합성(잔액·트랜잭션)이 요구되고, FDS 는 스트림 기반 통계 처리(KStream / windowedBy)가 핵심이다.

초기 설계 시 다음 세 가지 압박이 동시에 들어왔다.

1. **도메인 격리 요구**: 송금 도메인의 비즈니스 규칙(예: 일일 누적 한도, 블랙리스트, 잔액 차감)이 인프라(Spring Data JPA, Redis, Kafka)에 새지 않아야 한다.
2. **확장 압박**: FDS 가 단순 Consumer 에서 Kafka Streams 로, 다시 ML 기반 룰 평가기로 진화해야 한다. 각 단계에서 인프라 어댑터만 교체 가능해야 한다.
3. **현실적 제약**: Spring Boot 멀티모듈 환경에서 100% 순수 헥사고날을 유지하려면 Avro Kafka 모델·Spring main 부트스트랩·JPA 엔티티 변환 비용이 폭증한다. 학습용이 아닌 출시 가능한 구조여야 한다.

순수 헥사고날(Onion / Clean) 의 이상은 알지만, 100% 적용 시 “모든 인프라 객체를 application 포트로 한 번 더 감싸야 하는” 변환 비용이 발생한다. 송금 한 건당 변환 객체 수가 2배 이상이 된다.

> 참고: [현실적인 헥사고날 아키텍처와의 타협](../etc/현실적인 헥사고날 아키텍처와의 타협.md)

---

## Decision

**4계층(domain / application / infra / instances) 멀티모듈 구조를 채택하되, 순수성 100% 대신 80/20 절충선을 명시화한다.**

### 1. 모듈 구조

```text
services/
├── transfer/
│   ├── domain/         <- 순수 비즈니스 규칙 (User, Transaction, Money, AccountBalance)
│   ├── application/    <- 유스케이스 + 포트 (TransactionRegister, DailyTransferAmountCachePort)
│   ├── infra/          <- 어댑터 (RedisDailyTransferAmountCacheAdapter, KafkaTransferEventPublisher, JPA Repository)
│   └── instances/
│       ├── api/        <- Spring Boot main, Controller, Filter
│       └── transfer-relay/  <- Outbox Relay 전용 Spring Boot main
└── fds/
    ├── domain/
    ├── application/
    ├── infra/
    └── instances/api/
```

### 2. 의존성 규칙 (build-logic 의 Modules.kt 로 강제)

```text
Domain         <- 외부 의존 0
Application    <- Domain 만
Infra          <- Domain + Application (포트 구현)
Instances      <- Domain + Application + Infra (Spring 부트스트랩)
```

### 3. 명시적으로 허용한 타협

| 영역 | 이상 | 실제 채택 | 근거 |
|---|---|---|---|
| **Spring main 위치** | 별도 bootstrap 모듈 | `instances/api` 에 main 클래스 동거 | 단일 책임이지만 추가 모듈 추가 시 빌드 시간 2배 |
| **Avro Kafka 모델 노출** | Application 은 도메인 이벤트만 알아야 함 | Application 이 `TransferEventAvroModel` 을 직접 참조 | 변환 어댑터를 만드는 비용 > Avro 결합 비용. Avro 스키마는 도메인 이벤트와 1:1 매칭. |
| **JPA 엔티티 = 도메인 객체** | 절대 분리 | 일부 모듈에서 도메인 객체 = JPA 엔티티 | 송금 시 `User.accountBalance.withdrawOrThrow()` 가 호출되어야 함. 분리하면 매번 매핑. |
| **Controller** | Application 만 의존 | Controller 에서 Domain 객체 직접 참조 (DTO 변환은 한다) | DTO ↔ Domain 변환이 단일 Controller 안에서 끝난다면 허용 |

### 4. 절대 깨지 않는 선

- **Domain 모듈은 어떤 외부 라이브러리도 의존하지 않는다** (`com.fasterxml.jackson`, `org.springframework.*`, `javax.persistence.*` 금지).
- **Application 은 Spring annotation(`@Service`, `@Component`) 외 Spring infra 클래스 의존 금지**. `RedisTemplate`, `KafkaTemplate`, `JdbcTemplate` 은 절대 import 하지 않는다.
- **Infra 는 Application 의 포트를 구현하는 것 외에 다른 일을 하지 않는다**. 비즈니스 분기는 무조건 application 에 둔다.

---

## Consequences

### Positive

- **테스트 용이성**: Application 레이어는 포트만 의존하므로 Mock 으로 단위 테스트가 가능하다. `TransactionService` 의 일일 한도 검증 로직은 Redis 없이도 검증된다.
- **인프라 교체 비용 최소화**: Redis 캐시 → ElastiCache → Caffeine 으로 바꿀 때 `DailyTransferAmountCachePort` 구현체만 추가하면 된다.
- **도메인 응집도 보존**: 송금 비즈니스 규칙(블랙리스트, 잔액 검증, 한도 검증)이 `TransferValidator` 와 `Transaction.of()` 안에서만 변경된다.
- **점진적 확장**: `transfer-relay` 라는 별도 `instances/` 모듈을 추가하는 것만으로 Outbox Relay 를 독립 인스턴스로 분리할 수 있었다.

### Negative

- **학습 곡선**: 4계층 의존 규칙을 신규 입사자에게 30분 안에 설명하지 못하면 application 에 인프라 코드가 새기 시작한다. 실제로 초기에 `RedisTemplate` import 가 application 에 들어왔다가 PR 리뷰에서 제거되었다.
- **변환 비용**: `TransferRequestCommand → Transaction → TransferResponseCommand` 변환 단계가 항상 존재한다. 단순 CRUD 였다면 200 줄짜리 Controller 하나로 끝날 코드가 4 파일로 나뉜다.
- **빌드 시간**: 모듈 수가 늘어날수록 Gradle 그래프가 깊어진다. `build-logic` 으로 일부 완화했지만 (ADR-008 참고), 단일 모듈 구조 대비 15~20% 빌드 시간 증가는 감수했다.

### Neutral

- **Avro Kafka 모델의 application 노출**은 “Kafka 가 영구 의존성”이라는 결정의 부산물이다. Kafka 를 빼는 순간 application 도 손봐야 하지만, Kafka 는 시스템의 척추라 빠질 일이 없다.
- **JPA 엔티티 = 도메인 객체** 동거는 도메인이 커질 경우 분리 필요 신호다. 현재는 `User`, `Transaction` 이 50 줄 미만이라 분리 비용 > 이득.

---

## Alternatives Considered

### A. 클래식 3티어 (Controller / Service / Repository)

- **장점**: 학습 곡선 거의 없음. Spring Boot 기본 구조.
- **단점**: 도메인 규칙이 Service 에 산재한다. `TransferService` 가 Redis, Kafka, JPA, Validation 을 모두 알게 된다. 송금 도메인이 5 개 이상 비즈니스 규칙을 가지는 순간 Service 가 1,000 줄을 넘는다.
- **기각 사유**: FDS 도메인 추가 시 “송금 Service 안에 FDS 호출이 들어가는” 안티패턴이 불가피하다.

### B. Onion Architecture

- **장점**: 헥사고날과 거의 동일. 의존성 역전이 명확.
- **단점**: 계층 명칭(Domain Services / Application Services / Infrastructure Services)이 추상적이라 코드 위치 판단이 더 모호해진다.
- **기각 사유**: 헥사고날의 “포트/어댑터” 어휘가 신규 입사자 설명에 더 직관적이다.

### C. Clean Architecture (Uncle Bob)

- **장점**: Use Case 가 1급 클래스로 존재. Boundary 명확.
- **단점**: Entity → Use Case → Interactor → Controller 4단 변환이 강제된다. Java/Kotlin 보다 Go/Rust 에 더 맞는다.
- **기각 사유**: Use Case 별 별도 클래스로 분리 시 송금 한 건당 5개 클래스가 생긴다. Spring 의 `@Service` 와 결합이 어색하다.

### D. 단일 모듈 + 패키지 분리

- **장점**: 빌드 빠름. 의존성 그래프 단순.
- **단점**: “패키지 의존 규칙”을 강제할 컴파일러 레벨 도구가 없다. ArchUnit 으로 검증 가능하지만 런타임 비용.
- **기각 사유**: 멀티모듈 + `build-logic` 의 `compileOnly`/`api`/`implementation` 차이로 컴파일 시점에 규칙을 강제하는 것이 더 확실하다.

---

## Trade-offs

| 축 | 100% 순수 헥사고날 | 채택안 (80/20) | 3티어 |
|---|---|---|---|
| **도메인 격리** | 최상 | 상 | 하 |
| **테스트 용이성** | 최상 | 상 | 중 |
| **변환 비용 (객체 변환 수)** | 매 호출 4~5회 | 매 호출 2~3회 | 1~2회 |
| **빌드 시간** | 최상 (모듈 많음) | 중 | 최하 (모듈 1개) |
| **신규 입사자 온보딩** | 1주 | 2~3일 | 0.5일 |
| **인프라 교체 비용** | 최저 | 저 | 고 |
| **단기 개발 속도** | 최저 | 중 | 최고 |

채택안은 “테스트/격리는 80% 확보, 개발 속도는 60% 확보” 지점을 노렸다.

---

## References

### 코드 경로

- 모듈 정의: `build-logic/src/main/kotlin/transentia/Modules.kt`
- 송금 application: `services/transfer/application/src/main/kotlin/io/github/hyungkishin/transentia/application/TransactionService.kt`
- 송금 도메인: `services/transfer/domain/`
- 송금 infra: `services/transfer/infra/`
- FDS Kafka Streams: `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/adapter/in/messaging/FraudPatternStreamProcessor.kt`
- 헥사고날 결정 문서: `docs/etc/현실적인 헥사고날 아키텍처와의 타협.md`

### 외부 자료

- Alistair Cockburn, "Hexagonal Architecture" (2005)
- Vaughn Vernon, *Implementing Domain-Driven Design* (2013)
- Tom Hombergs, *Get Your Hands Dirty on Clean Architecture* (2019)

---

## Related ADRs

- ADR-002: Transactional Outbox Pattern (이벤트 발행은 infra 어댑터로 격리)
- ADR-008: Build Logic Convention Plugins (모듈 의존 규칙 강제)
