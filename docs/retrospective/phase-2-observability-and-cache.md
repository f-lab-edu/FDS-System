# Phase 2 회고 — 관측성과 캐시

> Phase 1이 끝났을 때 시스템은 "굴러는 가지만 들여다볼 수 없는" 상태였다.
> Relay가 균등하게 분배되는 건 단일 머신 벤치에서만 확인했고, 프로덕션 흐름에서 한 요청이 어떤 경로로 흘러가는지 따라가지 못했다. Phase 2는 그걸 들여다보기 위한 작업이었다.

---

## 1. 무엇을 만들었나

Phase 2의 결과물은 세 가지 묶음으로 정리된다.

1. **분산 트레이싱 전 구간 전파** — HTTP, `@Async` 스레드, Kafka Producer, Kafka Streams까지 같은 `traceId`가 따라간다.
2. **ELK Stack 도입** — Filebeat → Elasticsearch → Kibana. JSON 로그 기반.
3. **Redis 일일 한도 캐시** — `DailyTransferAmountCachePort`. 송금 누적액 조회를 DB가 아닌 Redis에서 한다.

세 개가 모두 "보이게 만들고, 빨리 확인하게 만들기" 같은 한 방향의 작업이다.

---

## 2. 처음에는 traceId가 일부 구간만 살아 있었다

송금 API에 요청이 들어오면 Spring 측 `ServerHttpObservationFilter`가 자동으로 `traceId`를 생성한다. 컨트롤러 안에서 로그를 찍으면 traceId가 같이 찍힌다. 여기까지는 별 의식 없이 동작했다.

그런데 Outbox 발행 시점에 traceId가 비어 있었다.

```
15:38:02.568 [ INFO] [524761ffb7da87cfa957ef60b888627a] o.a.k.c.p.ProducerConfig - ...
15:38:02.574 [ INFO] []                                  o.a.k.clients.Metadata - ...
                     ㄴ 없음
15:38:03.246 [ INFO] []                                  KafkaTransferEventPublisher - 전송 성공: eventId=266092862536421376
                     ㄴ 없음
```

처음에는 "MDC가 어디서 누락되지?"가 안 보였다. 한참 들여다본 뒤에야 원인이 잡혔다. 송금 API에서 Outbox 발행은 `@Async`로 분리된 스레드에서 처리되고, `@Async`는 새로운 스레드 풀에서 동작하므로 MDC와 Micrometer Observation Context가 자동으로 따라가지 않는다. 즉, traceId는 호출 스레드에만 있고 비동기 스레드에는 없다.

여기서 Phase 1의 결정 하나가 떠올랐다. "송금 성공 시 Kafka 직접 send, 실패 시만 Relay가 처리"하는 hybrid 구조(90b9682 커밋)였는데, 이 구조에서는 비동기 publish 경로가 늘 traceId 없이 동작하고 있었다. 한 달 정도 이걸 모르고 있었다.

---

## 3. 어떻게 풀었나 — 자동 / 수동 구간을 분리하기

이 시점에 처음 깨달은 게 있다. **Spring + Micrometer는 "모든 구간에서 자동으로 traceId가 전파된다"는 인상을 주지만 실제로는 그렇지 않다.** HTTP는 자동이고, Kafka Producer는 `setObservationEnabled(true)`만 켜면 자동이지만, `@Async` 스레드와 Kafka Streams는 수동으로 끼워 넣어야 한다.

| 구간 | 전파 방식 | 처리 위치 |
|---|---|---|
| HTTP 요청/응답 | 자동 | Micrometer Tracing 기본 동작 |
| `@Async` 스레드 | 수동 | `AsyncConfig.kt` — `ContextPropagatingTaskDecorator` |
| Kafka Producer | 설정 | `KafkaProducerConfig.kt` — `setObservationEnabled(true)` |
| Kafka Streams | 수동 | `TracingProcessor.kt` — header 직접 파싱 |

### 3.1 @Async — ContextPropagatingTaskDecorator

Spring Boot 3.0+에는 `ContextPropagatingTaskDecorator`가 들어 있다. 이걸 `ThreadPoolTaskExecutor`에 붙이면 MDC와 Micrometer Observation Context가 새 스레드로 따라간다.

```kotlin
// services/transfer/instances/api/src/main/kotlin/.../AsyncConfig.kt
@Bean("outboxEventExecutor")
fun outboxEventExecutor(): Executor {
    val executor = ThreadPoolTaskExecutor()
    executor.corePoolSize = 3
    executor.maxPoolSize = 10
    executor.queueCapacity = 50
    executor.setThreadNamePrefix("outbox-event-")
    executor.setTaskDecorator(ContextPropagatingTaskDecorator())  // 핵심 한 줄
    executor.initialize()
    return executor
}
```

이 한 줄을 넣고 나니 traceId가 비동기 스레드까지 살아 있었다.

```
15:40:21.561 [ INFO] [c74a3b531cb4e71a6f3860718bb001e3] KafkaTransferEventPublisher - 전송 성공
                     ㄴ 존재 한다
```

3b37ee3 커밋이 이 변경이다. 한 줄이 한 달의 미스를 풀었다.

### 3.2 Kafka Producer — setObservationEnabled

KafkaTemplate에 observation을 켜면 W3C `traceparent` 헤더가 메시지에 자동으로 들어간다.

```kotlin
@Bean
fun kafkaTemplate(): KafkaTemplate<K, V> {
    return KafkaTemplate(producerFactory()).apply {
        setObservationEnabled(true)
    }
}
```

이건 자동 영역이라 비교적 쉬웠다.

### 3.3 Kafka Streams — 직접 파싱

여기가 가장 까다로웠다. **Kafka Streams는 Micrometer Observation을 지원하지 않는다.** 즉, Streams 토폴로지로 들어온 레코드의 헤더에서 `traceparent`를 직접 읽어 MDC에 넣어야 한다.

```kotlin
// services/fds/infra/src/main/kotlin/.../TracingProcessor.kt
class TracingProcessor : FixedKeyProcessor<...> {
    override fun process(record: FixedKeyRecord<...>) {
        MDC.clear()  // 핵심
        val traceparent = record.headers().lastHeader("traceparent")?.value()?.let { String(it) }
        if (traceparent != null) {
            val parts = traceparent.split("-")
            if (parts.size >= 3) {
                MDC.put("traceId", parts[1])
                MDC.put("spanId", parts[2])
            }
        }
        context.forward(record)
    }
}
```

여기서 처음에 놓친 게 `MDC.clear()`다. Kafka Streams는 스레드 풀을 재사용한다. 이전 메시지 처리에서 MDC에 traceId가 남아 있으면, 다음 메시지(헤더 없는 경우)가 이전 메시지의 traceId로 잘못 로깅된다. 운영자가 Kibana에서 traceId로 검색했을 때 엉뚱한 로그가 묶여 나오는 버그였다.

또 한 가지, Kafka Streams 3.3+에서 `transformValues()`가 deprecated가 됐다. `FixedKeyProcessor` API로 옮겨야 했다. API 마이그레이션 자체는 단순했지만, 공식 문서가 3.3 변경점을 충분히 강조하지 않아 처음에는 deprecated warning을 무시했다가 한 번 빌드가 실패한 다음에야 옮겼다.

---

## 4. ELK Stack — 왜 Filebeat였나

ELK를 도입할 때 두 가지 선택지가 있었다.

| 방식 | 장점 | 단점 |
|---|---|---|
| 앱에서 ES로 직접 전송 (TCP appender) | 설정 단순 | ES 장애 시 앱이 블로킹, 네트워크 순단 시 로그 유실, 원본 미보존 |
| Filebeat 에이전트 | 앱 분리, 파일 보관, 버퍼링 | 설정 복잡, 컴포넌트 한 개 추가 |

FDS는 금융 도메인이다. **로그가 유실되면 사고 조사가 안 된다.** ES가 다운된 사이 송금 이벤트가 발생하면, 그 로그가 사라지는 게 직접 전송 방식의 가장 큰 문제였다. Filebeat 방식은 ES가 다운되어 있어도 로그가 로컬 파일에 쌓이고, ES 복구 후 Filebeat가 이어서 보낸다. 운영 관점에서 답이 분명했다.

```
[Application] → 파일 쓰기 → [transfer-api.log]
                                  ↓
                              [Filebeat] → [Elasticsearch] → [Kibana]
```

이 구조의 핵심은 **앱의 책임이 "파일 쓰기"까지로 줄어든다**는 점이다. 로그 수집/저장은 Filebeat와 ES의 책임이다. 책임 분리만으로 운영 복잡도가 한 단계 내려간다.

---

## 5. ELK에서 실수했던 두 가지

### 5.1 service 필드명 충돌

처음 LogstashEncoder에 customFields를 이렇게 줬다.

```xml
<customFields>{"service":"transfer-api"}</customFields>
```

ES에 인덱싱이 시작되자마자 매핑 에러가 떴다.

```
"reason": "object mapping for [service] tried to parse field [service] as object,
           but found a concrete value"
```

원인은 Filebeat ECS(Elastic Common Schema)였다. ECS에서 `service`는 object 타입(`service.name`, `service.version` 등)으로 예약된 필드인데, 우리는 string으로 사용했다. 결과적으로 인덱스 매핑이 충돌해서 새 로그가 안 들어왔다.

`app_name`으로 이름을 바꿔서 해결했다. 작은 일처럼 보이지만, **ECS 같은 표준 스키마가 어떤 필드를 예약하고 있는지 미리 알았어야 했다**. 도구를 도입할 때 그 도구가 따르는 컨벤션부터 확인하는 게 순서다.

### 5.2 Filebeat input 분리 누락

Tomcat AccessLog와 애플리케이션 로그를 같은 input으로 처리하려다가 한 번 실패했다. AccessLog는 JSON이 아니라 plain text였고, 같은 input에서 `json.keys_under_root: true`로 두면 AccessLog 파싱이 깨졌다.

결국 input을 두 개로 분리했다.

```yaml
filebeat.inputs:
  - type: log
    paths: [/var/log/apps/transfer-api.log, /var/log/apps/fds-api.log]
    json.keys_under_root: true

  - type: log
    paths: [/var/log/apps/access.log]
    # JSON 파싱 끔
```

이 분리를 처음에 빠뜨려서 며칠 동안 AccessLog가 인덱싱되지 않았다. 운영자가 "어제 송금 실패한 요청 trace" 찾으려 했는데 access 로그가 비어 있어서 시간이 늘어났다.

여기서 배운 건 **로그 포맷이 다르면 input도 분리해야 한다**는 단순한 원칙이다. 이건 매뉴얼에는 있지만 처음 도입할 때는 잘 안 보인다.

---

## 6. Redis 일일 한도 캐시 — 한 달간 비활성화된 코드

일일 송금 한도 체크는 도메인 정책상 필수였다. 사용자가 하루에 누적 얼마까지 송금했는지 확인하고, 한도를 넘으면 차단해야 한다. 처음 구현은 단순했다.

```kotlin
// 송금 시점에 매번 DB 조회
val todayAmount = transactionRepository.sumAmountByUserToday(userId)
if (todayAmount + newAmount > dailyLimit) throw OverLimitException()
```

이 코드는 작동했지만 두 가지 문제가 있었다.

1. **매 송금마다 transactions 테이블 풀스캔에 가까운 집계.** 인덱스를 (user_id, created_at)로 잡아도 같은 사용자가 하루 100건 송금하면 100번의 집계 쿼리가 나간다.
2. **부하 테스트에서 이 쿼리가 RT 상위에 올라왔다.** 송금 처리 RT의 30~40%를 이 한 쿼리가 차지했다.

캐시가 필요하다는 판단은 자연스러웠다. 선택지는 두 개였다.

| 캐시 | 장점 | 단점 |
|---|---|---|
| **Caffeine (in-memory)** | 외부 의존성 없음, RT 가장 빠름 | 인스턴스간 공유 안 됨, 송금 API가 N대일 때 무용 |
| **Redis** | 인스턴스간 공유, TTL 관리 | 외부 컴포넌트 추가 |

송금 API가 수평 확장될 예정이었으므로 Redis가 답이었다. 한 사용자의 누적액은 어떤 API 인스턴스가 처리하든 같아야 하기 때문이다.

그런데 여기서 한 달간의 미스가 있었다.

### 6.1 Redis 미도입 상태에서 한도 체크가 비활성화된 코드 1개월 방치

Phase 1에서 다른 작업에 우선순위가 밀려서 Redis 도입이 미뤄졌다. 그동안 임시로 일일 한도 체크 자체를 비활성화했다(주석 처리). 이게 한 달간 그대로 있었다. 한도 체크 없이 송금이 통과되고 있었던 것이다.

이게 운영 사고였다면 그 한 달이 그대로 컴플라이언스 이슈였다. 다행히 개발 환경이라 사고로 이어지진 않았지만, **"임시"라는 단어가 코드에 박히면 한 달은 가뿐히 넘긴다**는 걸 다시 확인했다.

### 6.2 어떻게 풀었나 — Port/Adapter 분리

이 사건 이후 두 가지를 같이 했다.

1. **포트를 먼저 정의해서 placeholder를 못 박았다.** `DailyTransferAmountCachePort`라는 인터페이스를 application 레이어에 만들고, "구현체가 없으면 빌드가 안 된다"는 상태로 만들었다. 주석 처리로 우회할 수 없게.
2. **NoOp 어댑터 대신 RedisDailyTransferAmountCacheAdapter를 바로 붙였다.** 처음부터 진짜 구현체로 가야 한다는 압박을 코드 차원에서 만들었다.

```kotlin
// services/transfer/application/src/main/kotlin/.../DailyTransferAmountCachePort.kt
interface DailyTransferAmountCachePort {
    fun getTodayAmount(userId: Long): Long
    fun addTodayAmount(userId: Long, amount: Long): Long
}

// services/transfer/infra/src/main/kotlin/.../RedisDailyTransferAmountCacheAdapter.kt
@Component
class RedisDailyTransferAmountCacheAdapter(...) : DailyTransferAmountCachePort {
    // 키: daily:transfer:{userId}:{yyyyMMdd}
    // TTL: ~26시간 (하루 + 약간의 여유)
}
```

키 형식 `daily:transfer:{userId}:{yyyyMMdd}`, TTL 약 26시간. 자정에 정확히 끊기면 자정 직후 송금이 새 키를 만들면서 0부터 시작한다. 26시간으로 둔 건 자정 부근 클럭 스큐를 흡수하기 위해서다.

### 6.3 무엇이 더 좋아졌나

| 지표 | Before(DB 집계) | After(Redis) |
|---|---|---|
| 평균 RT (한도 조회 부분) | 12~15ms | 1~2ms |
| 송금 전체 RT에서 차지 비중 | 30~40% | 5% 미만 |
| DB 쿼리 수 (사용자당 1일) | 송금 건수만큼 N회 | 0회 (Redis hit) + 변경 시 1회 INCR |

수치 자체보다 더 큰 변화는 **DB가 "조회"를 안 하게 됐다**는 점이다. 송금 마스터 데이터는 여전히 DB에 있지만, 빈번한 read는 Redis로 옮겨졌다. DB는 source of truth로 남고, Redis는 query cache로 동작한다.

---

## 7. 메트릭 요약

| 영역 | 지표 | 결과 |
|---|---|---|
| traceId 전파 | 전 구간 일관성 | API → Outbox → Kafka → Streams → FDS 끝까지 동일 traceId 유지 |
| ELK | 로그 검색 응답 시간 (Kibana) | traceId 기준 1초 이내 (단일 인덱스 < 100GB 가정) |
| Redis 캐시 | 일일 한도 조회 RT | 12~15ms → 1~2ms |
| Redis 캐시 | 송금 RT에서 한도 조회 비중 | 30~40% → 5% 미만 |

---

## 8. 내가 했던 실수

**(1) "Micrometer가 다 해준다"고 가정했다.** HTTP만 자동, 나머지는 수동이라는 걸 한 달 뒤에 알았다. 도구가 어디까지 자동인지 명확히 확인하지 않고 "당연히 되겠지"라고 넘어간 게 가장 큰 미스였다.

**(2) Kafka Streams의 MDC.clear()를 빠뜨려서 traceId가 오염됐다.** 운영자가 Kibana로 검색했을 때 다른 traceId까지 같이 묶여 나오는 버그였다. 스레드 풀 재사용 패턴을 떠올렸어야 했다. "프로세서가 끝나면 다음 호출에서 새 컨텍스트로 시작한다"는 무의식적 가정이 깨졌다.

**(3) ECS service 필드 충돌을 매뉴얼만 한 번 더 봤으면 피할 수 있었다.** ELK 도입 시 그 도구가 어떤 컨벤션을 따르는지 먼저 확인하는 게 순서였다. 매뉴얼은 의외로 짧다.

**(4) 일일 한도 체크를 "임시 비활성화"로 한 달 방치했다.** 메모리 캐시로라도 임시 구현하고 가야 했다. "Redis 도입 전까지"라는 단서가 코드에 박히면 그 단서는 정량적 deadline이 없는 한 영원하다.

**(5) AccessLog input을 application 로그와 같은 input으로 처리하려고 했다.** 포맷이 다른 로그는 input을 분리해야 한다. 이건 ELK 매뉴얼에 적혀 있지만 처음 도입할 때 잘 안 보인다.

---

## 9. 남은 리스크 / Phase 3로 넘어가는 지점

- **APM 대시보드가 없다.** ELK는 로그 검색까지다. RT 분포, 에러율, throughput을 시계열로 보려면 Prometheus + Grafana 또는 OpenSearch APM이 필요하다. 현재는 Kibana에서 SQL-like 쿼리로 흉내 내고 있다.
- **Filebeat의 backpressure 대응이 검증되지 않았다.** ES가 느려질 때 Filebeat가 디스크에 얼마나 쌓을 수 있는지 정확한 limit이 설정되지 않았다. 디스크 가득 차면 앱이 로그 쓰기에서 실패한다.
- **Redis가 단일 장애점이다.** 현재는 single-node Redis. Sentinel 또는 Cluster로 가야 한다.
- **캐시 무효화 시나리오가 정리되지 않았다.** 일일 한도는 INCR 방식이라 동기화 문제는 적지만, 향후 다른 캐시(예: 사용자 프로필)를 추가하면 invalidation 정책을 설계해야 한다.
- **Kafka Streams의 처리 분석이 부족하다.** Phase 3에서 본격적으로 다루게 된다.

---

## 10. 빅테크 인터뷰 Q&A

### Q1. 왜 Brave (Zipkin Reporter)를 안 쓰고 Micrometer + OpenTelemetry 브릿지를 썼나?

Brave는 Zipkin 생태계에 묶여 있다. W3C Trace Context를 부분적으로 지원하지만 그 동작이 라이브러리 버전에 따라 미묘하게 달라진다. 반면 Micrometer Tracing + OpenTelemetry 브릿지는 W3C 표준을 명시적으로 따르고, 향후 OpenSearch APM이나 다른 OTel collector로 옮길 때 추가 작업이 적다. 또 Spring Boot 3.0+에서 Micrometer가 사실상 기본이라, 라이브러리 갈등이 적었다. "Zipkin을 안 쓴 게 아니라 표준 쪽으로 갔다"는 게 더 정확한 표현이다.

### Q2. Redis 대신 Memcached는 왜 안 썼나? Memcached가 더 빠르지 않나?

송금 누적액은 단순 키-값 read/write가 아니라 atomic INCR이 필요하다. Memcached도 increment를 지원하지만 expire와 increment를 atomic하게 묶는 게 까다롭다. Redis는 INCR + EXPIRE를 Lua script로 한 번에 묶을 수 있다. 그리고 향후 비밀번호 시도 횟수, 일일 거래 횟수, 이상거래 카운터 등 비슷한 "TTL + atomic counter" 패턴이 더 늘어날 게 분명했다. Memcached가 더 빠른 건 단순 GET/SET 시나리오이고, 우리 시나리오는 그게 아니었다. RT 차이도 1~2ms 수준이라 의미 있는 차이는 아니다.

### Q3. 캐시 무효화 전략은 무엇인가? Cache-aside, Write-through, Write-behind 중 어느 것인가?

일일 한도는 정확히는 "increment 기반 캐시"라 정통 3분류에 깔끔히 안 맞는다. 굳이 분류하면 **write-through에 가깝다**. 송금 처리 시점에 (a) DB의 transaction 저장, (b) Redis INCR을 같은 흐름에서 처리한다. 다만 (a)와 (b) 사이에 명시적 분산 트랜잭션이 없으므로, DB는 success인데 Redis만 실패한 경우 일시적 불일치가 발생한다. 이걸 막기 위해 두 가지 안전망이 있다. 첫째, 매일 자정 직후 배치가 Redis 키를 다시 계산해 보정한다(현재는 TODO). 둘째, Redis 미스 시 DB에서 그 날짜 합계를 재집계해 캐시를 다시 채운다. "캐시가 source of truth가 아니다"라는 전제를 강하게 잡았다.

### Q4. @Async에 ContextPropagatingTaskDecorator를 붙이면 성능 비용이 있나?

미세하게 있다. 각 task 제출 시 MDC + Observation Context의 snapshot을 만들고, task 실행 시 그걸 새 스레드에 복원한다. 단순 measure로는 task당 수십 마이크로초 수준이다. 송금 API의 평균 RT가 수십~수백 밀리초이므로 거의 영향이 없다. 더 정확히는, "traceId 없는 운영 디버깅"이 가져오는 비용이 이 마이크로초 비용보다 천 배는 크다. 트레이드오프가 명확하다.

### Q5. ELK 대신 Loki / OpenSearch / Datadog 같은 대안은 왜 안 봤나?

봤다. 결정 기준은 두 개였다. (a) **운영 학습 곡선**, (b) **금전 비용**. Datadog은 SaaS 비용이 빨리 누적되고, 토이 프로젝트 단계에서 도입할 만한 수준이 아니었다. Loki는 라벨 기반 인덱싱이라 traceId처럼 cardinality가 높은 필드 검색에 최적이 아니다. OpenSearch는 ELK와 거의 같지만 Elastic 라이선스 이슈를 회피하는 fork다. 결국 "공식 문서가 가장 두껍고, Filebeat/Logstash 등 주변 도구가 가장 잘 정리된" ELK를 골랐다. 라이선스가 운영 문제가 되는 시점에는 OpenSearch로 옮기는 게 가장 자연스러운 경로다.

---

## 관련 코드 경로

- `services/transfer/instances/api/src/main/kotlin/io/github/hyungkishin/transentia/api/config/AsyncConfig.kt`
- `services/fds/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/config/TracingProcessor.kt`
- `services/transfer/application/src/main/kotlin/io/github/hyungkishin/transentia/application/required/DailyTransferAmountCachePort.kt`
- `services/transfer/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/redis/RedisDailyTransferAmountCacheAdapter.kt`
- `services/transfer/domain/src/main/kotlin/io/github/hyungkishin/transentia/container/model/user/DailyTransferLimit.kt`
- `docker-compose.yml` (Elasticsearch/Kibana/Filebeat 정의)
- `monitoring/filebeat.yml`
- ELK 도입 상세: `docs/etc/observability-setup - ELK_Stack.md`
- W3C Trace Context 정리: `docs/etc/W3C Trace Context.md`
