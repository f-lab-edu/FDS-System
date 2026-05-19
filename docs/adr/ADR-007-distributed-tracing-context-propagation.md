# ADR-007: 분산 트레이싱 컨텍스트 전파 전략

- **Status**: Accepted
- **Date**: 2026-05-15
- **Deciders**: Backend Lead
- **Tags**: tracing, observability, w3c, micrometer, kafka, async

---

## Context

Transentia 는 다음 경로로 하나의 송금 요청이 흐른다.

```text
Client
  -> HTTP -> Transfer API (@RestController)
              -> @Async -> Kafka Producer
                              -> Kafka topic (transfer-transaction-events)
                                            -> Kafka Streams (FDS)
                                            -> Kafka Consumer (FDS)
                                                            -> @Async -> downstream
```

**한 요청을 끝까지 추적하려면 동일한 `traceId` 가 위 6단계를 모두 통과해야 한다.** 한 단계라도 끊기면 분산 로그 검색이 불가능해진다.

### 끊기는 지점 (실측)

1. **HTTP**: Spring Boot 의 `ServerHttpObservationFilter` 가 자동으로 처리. **자동.**
2. **`@Async` 스레드**: ThreadLocal 기반 MDC 가 새 스레드로 복사되지 않음. **수동 필요.**
3. **Kafka Producer**: `KafkaTemplate` 이 메시지 헤더에 `traceparent` 를 자동으로 넣지 않음. **수동 필요.**
4. **Kafka Consumer**: `@KafkaListener` 는 헤더에서 자동 추출. **자동 (observation 활성화 시).**
5. **Kafka Streams**: **Micrometer Observation 지원 없음**. 가장 까다로움. **수동 파싱 필요.**

> 참고: [observability-setup - ELK_Stack.md](../etc/observability-setup - ELK_Stack.md), [W3C Trace Context.md](../etc/W3C Trace Context.md)

### 표준 선택

- B3 (Zipkin) vs W3C Trace Context.
- 현재 OpenTelemetry / 대부분 APM 도구가 W3C 를 기본 채택.

---

## Decision

**W3C Trace Context 표준을 채택한다. Micrometer Tracing + OpenTelemetry 브릿지를 기반으로 다음 4 지점에 수동 전파 코드를 추가한다.**

| 구간 | 전파 방식 | 구현 |
|---|---|---|
| HTTP 요청/응답 | 자동 | Micrometer `ServerHttpObservationFilter` |
| `@Async` 스레드 | 수동 | `AsyncConfig` + `ContextPropagatingTaskDecorator` |
| Kafka Producer | 자동 (설정만) | `KafkaTemplate.setObservationEnabled(true)` |
| Kafka Consumer | 자동 (설정만) | `ConcurrentKafkaListenerContainerFactory.containerProperties.observationEnabled = true` |
| Kafka Streams | 수동 | `TracingProcessor` (`FixedKeyProcessor`) |

### 1. 의존성 (build-logic 의 `SpringBootAppConventionPlugin`)

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
}
```

### 2. `@Async` 전파: ContextPropagatingTaskDecorator

`services/transfer/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/api/config/AsyncConfig.kt:15`

```kotlin
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean("outboxEventExecutor")
    fun outboxEventExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 3
        executor.maxPoolSize = 10
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("outbox-event-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())

        // Spring Boot 3.0+ ContextPropagatingTaskDecorator
        // MDC + Micrometer Observation Context 모두 전파
        executor.setTaskDecorator(ContextPropagatingTaskDecorator())

        executor.initialize()
        return executor
    }
}
```

**효과 (실측):**

```text
# 적용 전
15:38:03.246 [ INFO] [] i.g.h.t.i.a.KafkaTransferEventPublisher - Kafka 전송 성공
                       ^^ traceId 누락

# 적용 후
15:40:21.561 [ INFO] [c74a3b531cb4e71a6f3860718bb001e3] i.g.h.t.i.a.KafkaTransferEventPublisher - Kafka 전송 성공
                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ traceId 존재
```

### 3. Kafka Producer: setObservationEnabled

```kotlin
@Configuration
class KafkaProducerConfig<K, V : SpecificRecordBase>(...) {
    @Bean
    fun kafkaTemplate(): KafkaTemplate<K, V> {
        return KafkaTemplate(producerFactory()).apply {
            setObservationEnabled(true)  // traceparent 헤더 자동 주입
        }
    }
}
```

활성화 전: Kafka UI 에서 메시지 헤더가 빈 상태. 활성화 후: `traceparent` 헤더 자동 추가.

### 4. Kafka Streams: TracingProcessor 수동 파싱

`services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/config/TracingProcessor.kt`

```kotlin
class TracingProcessor : FixedKeyProcessor<String, TransferEventAvroModel, TransferEventAvroModel> {
    private lateinit var context: FixedKeyProcessorContext<String, TransferEventAvroModel>

    override fun init(context: FixedKeyProcessorContext<String, TransferEventAvroModel>) {
        this.context = context
    }

    override fun process(record: FixedKeyRecord<String, TransferEventAvroModel>) {
        MDC.clear()  // 스레드 풀 재사용 대비

        val traceparent = record.headers().lastHeader("traceparent")?.value()?.let { String(it) }
        if (traceparent != null) {
            // traceparent 형식: 00-{traceId}-{spanId}-{flags}
            val parts = traceparent.split("-")
            if (parts.size >= 3) {
                MDC.put("traceId", parts[1])
                MDC.put("spanId", parts[2])
            }
        }
        context.forward(record)
    }

    override fun close() {
        MDC.clear()
    }
}
```

#### 왜 `MDC.clear()` 가 두 번 (init/process/close)?

Kafka Streams 는 스레드 풀을 재사용한다. **이전 메시지의 traceId 가 MDC 에 남아있으면 헤더 없는 다음 메시지가 잘못된 traceId 로 로깅된다.**

```text
메시지 A (traceId=aaa) -> 스레드 #1 처리 -> MDC: traceId=aaa
메시지 B (헤더 없음)     -> 스레드 #1 재사용 -> MDC.clear() 없으면 traceId=aaa 잔존
                                              -> 메시지 B 가 aaa 로 잘못 로깅됨
```

#### 왜 `FixedKeyProcessor` 인가?

Kafka Streams 3.3+ 에서 `transformValues()` 가 deprecated 되었다. 새 API 는 `FixedKeyProcessor` (키 변경 불가) / `Processor` (키 변경 가능). 본 케이스는 키를 바꾸지 않으므로 `FixedKeyProcessor` 가 적합.

### 5. application.yml 샘플링

```yaml
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 개발 환경 100% 샘플링
```

운영 환경은 `0.1` (10%) 권장. 본 결정은 별도 ADR 대상.

---

## Consequences

### Positive

- **전 구간 traceId 일관성**: HTTP → @Async → Kafka → Streams → Consumer 모두 동일 traceId.
- **Kibana 단일 쿼리**: `traceId: "cc432..."` 한 줄로 모든 서비스 로그 검색.
- **표준 준수**: W3C Trace Context 를 따르므로 향후 Jaeger / Tempo / Datadog 등 어떤 APM 에도 결합 가능.
- **장애 분석 단축**: “느린 송금” 신고 들어오면 traceId 로 어떤 구간이 느렸는지 즉시 식별.
- **샘플링 제어 가능**: `sampling.probability` 로 운영 비용 조절.

### Negative

- **수동 코드 유지 부담**: `TracingProcessor`, `AsyncConfig` 가 명시적으로 존재한다. Kafka Streams 가 향후 Observation 을 지원하면 제거 가능하지만 현재는 의존성 코드.
- **MDC 잔존 버그 위험**: `MDC.clear()` 누락 시 traceId 오염. 명시적 테스트 케이스 필요.
- **traceparent 형식 의존**: W3C 표준이 바뀌면 파싱 로직 수정 필요. 현재는 `version-traceId-spanId-flags` 가 고정.
- **샘플링 시 검색 누락**: 운영 환경에서 10% 샘플링이면 90% 요청은 traceId 가 ES 에 없을 수 있다 (또는 있지만 부분만 있음). 정책 명확화 필요.

### Neutral

- **`addContextPropagatingTaskDecorator` vs `MdcTaskDecorator`**: 전자는 MDC + Micrometer Observation Context 모두 전파. 후자는 MDC 만. 본 채택은 전자 (Spring Boot 3.0+ 기본).
- **Brave 자동설정 제외**: `application.yml` 에서 명시적으로 제외.
  ```yaml
  spring:
    autoconfigure:
      exclude:
        - org.springframework.boot.actuate.autoconfigure.tracing.BraveAutoConfiguration
  ```
  OTel 브릿지와 충돌 방지.

---

## Alternatives Considered

### A. OpenTelemetry SDK 직접 사용

- **장점**: 가장 표준에 가까움. 모든 backend 호환.
- **단점**:
  - Spring Boot 의 자동설정과 일부 충돌.
  - 의존성 트리 무거움 (OTel agent + SDK + exporter).
  - 학습 곡선 가파름.
- **기각 사유**: Micrometer Tracing + OTel bridge 가 Spring 생태계와 자연스럽게 결합되며, 결국 OTel 표준에 도달한다. 추상화 한 단계 더 사용.

### B. Spring Cloud Sleuth

- **상태**: **End-of-Life (Spring Boot 3.x 미지원)**.
- **기각 사유**: Micrometer Tracing 으로 대체된 공식 후속.

### C. Brave (Zipkin)

- **장점**: 안정적. 검증된 트레이서.
- **단점**:
  - B3 표준 기본 (W3C 지원 가능하지만 추가 설정).
  - Micrometer 호환은 되지만 OTel ecosystem 과 거리.
- **기각 사유**: W3C / OTel 이 대세. Brave 는 레거시.

### D. Manual MDC Propagation Everywhere

- **장점**: 외부 라이브러리 의존 0.
- **단점**:
  - 모든 `@Async`, `ExecutorService.submit()` 호출에 수동 코드.
  - Kafka 헤더 직렬화/역직렬화 직접 구현.
  - traceparent 표준 수동 준수.
- **기각 사유**: 유지보수 지옥.

---

## Trade-offs

| 축 | Micrometer + OTel Bridge (채택) | OTel SDK 직접 | Sleuth | Brave |
|---|---|---|---|---|
| **표준 준수** | W3C (자동) | W3C (직접) | W3C | B3 기본 |
| **Spring 통합** | 최상 | 중 (자동설정 충돌) | 최상 (단, EOL) | 상 |
| **학습 곡선** | 완만 | 가파름 | 완만 | 중 |
| **OTel ecosystem** | 호환 | 네이티브 | 호환 | 어색 |
| **운영 부담** | 저 | 중 (SDK 관리) | 저 (단, EOL) | 저 |

---

## Operational Considerations

### 샘플링 정책

- 개발/스테이징: `probability: 1.0` (100%).
- 운영: `probability: 0.1` (10%) 권장.
- VIP 사용자/특정 endpoint 는 강제 100% 샘플링 (`X-Force-Trace` 헤더 등) 향후 검토.

### Kafka Streams 토폴로지 배치

`TracingProcessor` 는 토폴로지의 **첫 단계** 에 배치해야 한다. Source 이후 즉시 MDC 를 설정하지 않으면 그 사이 로그에 traceId 가 빠진다.

```kotlin
input
    .processValues { TracingProcessorSupplier() }   // <- 가장 먼저
    .selectKey { ... }
    .groupByKey()
    ...
```

### Kafka 헤더 크기 한계

`traceparent` 헤더는 ~55 bytes. 메시지당 부담 미미.

### 검증 도구

- Kafka UI 의 Messages 탭에서 헤더에 `traceparent` 존재 확인.
- Kibana Discover 에서 `traceId` 필드 존재 확인.

---

## References

### 코드 경로

- AsyncConfig: `services/transfer/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/api/config/AsyncConfig.kt:15`
- TracingProcessor: `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/config/TracingProcessor.kt`
- TracingConfiguration (HTTP): `common/common-application/src/main/kotlin/io/github/hyungkishin/transentia/common/http/tracing/TracingConfiguration.kt`
- Transfer API application.yml: `services/transfer/instances/api/src/main/resources/application.yml:100-103`
- 상세 문서: `docs/etc/observability-setup - ELK_Stack.md`, `docs/etc/W3C Trace Context.md`
- 관련 커밋: `3b37ee3 feat: 분산 트레이싱 컨텍스트 전파 구현`

### 외부 자료

- W3C Trace Context Specification, https://www.w3.org/TR/trace-context/
- Micrometer Tracing Documentation
- OpenTelemetry Java SDK
- Apache Kafka Streams Processor API

---

## Related ADRs

- ADR-002: Transactional Outbox (Outbox 의 `headers` 컬럼에 traceparent 저장)
- ADR-004: Kafka Streams (TracingProcessor 는 Streams 토폴로지의 첫 단계)
- ADR-006: ELK Observability (traceId 가 ES 검색 키)
