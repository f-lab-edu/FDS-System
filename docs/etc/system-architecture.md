# System Architecture

## 1. 개요

**Transentia**는 Kotlin + Spring Boot 기반 멀티모듈 백엔드로, 송금 처리(Transfer), 이상거래탐지(FDS), ELK 기반 로그 수집·시각화를 통합한 핀테크 학습 프로젝트다.

---

## 2. System Context

외부 행위자가 Nginx를 통해 백엔드 시스템과 상호작용하는 전체 맥락을 표시한다.

```mermaid
C4Context
    title System Context — Transentia

    Person(user, "웹/모바일 사용자", "송금 요청, 내역 조회")
    Person(admin, "관리자", "Kibana 대시보드, Kafka UI 모니터링")

    System_Boundary(backend, "Transentia Backend") {
        System(nginx, "Nginx Reverse Proxy", "라우팅 / LB")
        System(transfer, "Transfer-API", "송금 처리 서비스")
        System(fds, "FDS-API", "이상거래 탐지 서비스")
    }

    System_Ext(kibana, "Kibana", "로그 시각화 (port 5601)")
    System_Ext(kafkaui, "Kafka UI", "토픽 모니터링 (port 9000)")
    System_Ext(grafana, "Grafana", "메트릭 대시보드 (port 3000)")

    Rel(user, nginx, "HTTPS / HTTP", "REST JSON")
    Rel(admin, kibana, "브라우저")
    Rel(admin, kafkaui, "브라우저")
    Rel(admin, grafana, "브라우저")
    Rel(nginx, transfer, "프록시 /api/transfers")
    Rel(nginx, fds, "프록시 /api/fds")
```

---

## 3. Container View

전체 인프라 컴포넌트와 데이터 흐름을 표시한다.

```mermaid
flowchart LR
    subgraph Clients["외부 클라이언트"]
        WEB["웹 브라우저"]
        MOB["모바일 앱"]
    end

    subgraph Gateway["진입점"]
        NGINX["Nginx\n:80\n/api/transfers → transfer-api\n/api/fds → fds-api"]
    end

    subgraph AppLayer["애플리케이션 계층"]
        TAPI["Transfer-API\n:8080\nSpring Boot"]
        RELAY["Transfer-Relay\nSpring Batch\n(Outbox 실패 복구)"]
        FAPI["FDS-API\n:8082\nSpring Boot + Kafka Streams"]
    end

    subgraph Messaging["메시지 브로커"]
        KAFKA["Kafka :9092\ntopics:\n• transfer-transaction-events\n• fds-analysis-results\n• suspicious-patterns\n• transfer-complete-events"]
        SR["Schema Registry\n:8085"]
    end

    subgraph Storage["영속성"]
        PG["PostgreSQL :5432\nDB: transfer"]
        REDIS["Redis :6379\nDailyTransfer 한도 캐시\n(T2 도입 예정)"]
    end

    subgraph Observability["관측성"]
        ES["Elasticsearch\n:9200"]
        KIBANA["Kibana\n:5601"]
        FB["Filebeat\n로그 수집"]
        PROM["Prometheus\n:9090"]
        GRAFANA["Grafana\n:3000"]
    end

    WEB --> NGINX
    MOB --> NGINX
    NGINX -->|"POST /transfers\nGET /transfers/:id"| TAPI
    NGINX --> FAPI

    TAPI -->|"Avro 이벤트 발행\n(traceId header)"| KAFKA
    TAPI --> PG
    TAPI --> REDIS
    TAPI --> SR

    RELAY -->|"미발송 Outbox 재발행"| KAFKA
    RELAY --> PG

    KAFKA -->|"transfer-transaction-events"| FAPI
    FAPI -->|"fds-analysis-results\nsuspicious-patterns"| KAFKA
    FAPI --> PG
    FAPI --> SR

    TAPI -->|"애플리케이션 로그"| FB
    FAPI -->|"애플리케이션 로그"| FB
    FB --> ES
    ES --> KIBANA

    TAPI -->|"/actuator/prometheus"| PROM
    FAPI -->|"/actuator/prometheus"| PROM
    PROM --> GRAFANA
```

---

## 4. 헥사고날 계층 다이어그램

Transfer 서비스를 기준으로 헥사고날 아키텍처의 레이어 관계를 표시한다. FDS 서비스도 동일한 구조를 따른다.

```mermaid
flowchart TB
    subgraph instances["instances/api (조립 계층)"]
        CTRL["TransferController\n@RestController /transfers"]
        APP_CFG["AsyncConfig\n(ContextPropagatingTaskDecorator)"]
    end

    subgraph application["application (포트 계층)"]
        PROV["Provided Ports\nTransactionRegister\nTransactionHistoryRegister"]
        REQ["Required Ports\nTransactionRepository\nAccountBalanceRepository\nUserRepository\nDailyTransferAmountCachePort\nTransferEventsOutboxRepository\nHybridFdsEventPublisher"]
        SVC["TransactionService\nTransactionHistoryService"]
    end

    subgraph domain["domain (순수 도메인)"]
        MODEL["모델\nTransaction, AccountBalance\nUser, Money, DailyTransferLimit"]
        ENUM["열거형\nTransactionStatus, UserRole\nUserStatus"]
        EVENT["도메인 이벤트\nTransferEvent"]
        VALIDATOR["TransferValidator"]
    end

    subgraph infra["infra (어댑터 계층)"]
        JPA["JPA Adapters\nTransactionPersistenceAdapter\nAccountBalancePersistenceAdapter\nUserPersistenceAdapter"]
        KAFKAADPT["KafkaTransferEventPublisher\n(Outbox 패턴)"]
        SNOWFLAKE["Snowflake ID Generator"]
    end

    CTRL --> PROV
    SVC --> REQ
    PROV --> SVC
    SVC --> MODEL
    SVC --> VALIDATOR
    JPA --> REQ
    KAFKAADPT --> REQ
    instances -.->|"의존성 주입"| infra
```

---

## 5. 분산 트레이싱 흐름

커밋 `3b37ee3`에서 구현된 traceId 전파 경로다. Transfer-API → Kafka 헤더 → FDS-API까지 동일한 traceId가 유지된다.

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant TAPI as Transfer-API<br/>(:8080)
    participant Kafka as Kafka<br/>(transfer-transaction-events)
    participant FAPI as FDS-API<br/>(:8082)

    Client->>TAPI: POST /transfers
    activate TAPI
    Note over TAPI: Micrometer Observation<br/>traceId 생성

    TAPI->>TAPI: AsyncConfig<br/>ContextPropagatingTaskDecorator<br/>@Async 스레드에 Context 전파

    TAPI->>Kafka: Avro 이벤트 발행<br/>Header: traceparent=00-{traceId}-{spanId}-01

    TAPI-->>Client: TransferResponse
    deactivate TAPI

    Kafka->>FAPI: 이벤트 소비<br/>(Kafka Streams)
    activate FAPI
    Note over FAPI: TracingProcessor<br/>헤더에서 traceparent 파싱<br/>MDC.put("traceId", ...)

    FAPI->>Kafka: fds-analysis-results 발행<br/>(동일 traceId 유지)
    deactivate FAPI

    Note over TAPI,FAPI: Filebeat → Elasticsearch<br/>동일 traceId로 로그 조회 가능
```

---

## 6. FE 경계 (Nginx Gateway)

Nginx가 정적 자원 제공과 API 게이트웨이 역할을 함께 수행한다. FE 노드는 현재 구현 범위 외의 추상 플레이스홀더다.

```mermaid
flowchart LR
    subgraph FE["FE 영역 (추상 플레이스홀더)"]
        SPA["SPA / Static Assets\n(Next.js / React 등)"]
    end

    subgraph NGINX_BLOCK["Nginx :80"]
        STATIC["정적 자원 서빙\n(향후)"]
        PROXY_T["location /api/transfers\n→ upstream transfer_api\n(Round-Robin LB)"]
        PROXY_F["location /api/fds\n→ upstream fds_api"]
        HEALTH["location /health\n→ 200 OK (LB health check)"]
    end

    subgraph BE["백엔드 서비스"]
        TAPI1["transfer-api-1:8080"]
        TAPI2["transfer-api-2:8080"]
        FAPI1["fds-api-1:8082"]
        FAPI2["fds-api-2:8082"]
    end

    SPA -->|"API 호출"| PROXY_T
    SPA -->|"API 호출"| PROXY_F
    STATIC -.->|"정적 자원 (향후)"| SPA

    PROXY_T --> TAPI1
    PROXY_T --> TAPI2
    PROXY_F --> FAPI1
    PROXY_F --> FAPI2
```

---

## 7. 주요 API 목록

컨트롤러 소스(`services/transfer/instances/api/src`)에서 직접 확인한 엔드포인트다.

### Transfer-API (port 8080)

| 메서드 | 경로 | 설명 | 컨트롤러 |
|--------|------|------|----------|
| `POST` | `/transfers` | 송금 처리 (Avro 이벤트 발행, Outbox 패턴) | `TransferController#create` |
| `GET` | `/transfers/{transactionId}` | 단건 조회 | `TransferController#getTransfer` |
| `GET` | `/actuator/health` | 헬스체크 (Nginx LB용) | Spring Actuator |
| `GET` | `/actuator/prometheus` | Prometheus 메트릭 스크랩 | Spring Actuator |

### FDS-API (port 8082)

| 메서드 | 경로 | 설명 | 비고 |
|--------|------|------|------|
| `GET` | `/actuator/health` | 헬스체크 | Spring Actuator |
| `GET` | `/actuator/prometheus` | 메트릭 | Spring Actuator |

> FDS-API는 REST 엔드포인트를 직접 노출하지 않고, Kafka Streams(`transfer-transaction-events` 구독)를 통해 이벤트를 수신하여 `fds-analysis-results` / `suspicious-patterns` 토픽으로 결과를 발행하는 구조다.

### Kafka 토픽 정의

| 토픽 | 발행자 | 소비자 | 설명 |
|------|--------|--------|------|
| `transfer-transaction-events` | Transfer-API | FDS-API | 송금 완료 이벤트 (Avro) |
| `transfer-complete-events` | Transfer-API | — | 송금 완료 알림용 |
| `fds-analysis-results` | FDS-API | — | FDS 분석 결과 |
| `suspicious-patterns` | FDS-API | — | 10분 윈도우 이상패턴 집계 |
