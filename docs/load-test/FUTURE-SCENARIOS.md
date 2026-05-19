# 신규 시나리오 제안

현재 구현된 시나리오가 Transfer API의 동시성과 락 전략에 집중했다면,
이 문서는 **ELK 스택 통합(커밋 d463d3a)**, **분산 트레이싱(커밋 3b37ee3)**,
**Kafka Streams FDS**, **Redis 캐시 도입** 맥락에서 추가로 설계할 시나리오를 정의한다.
코드 없이 설계 수준으로 기술한다.

---

## 목차

1. [FDS Kafka Streams 처리량 부하](#1-fds-kafka-streams-처리량-부하)
2. [일일 한도 캐시 비교 — Redis 도입 전·후](#2-일일-한도-캐시-비교--redis-도입-전후)
3. [ELK 로그 적재 한계 — Filebeat → Elasticsearch 지연](#3-elk-로그-적재-한계--filebeat--elasticsearch-지연)
4. [분산 트레이싱 오버헤드 — sampling 1.0 vs 0.1](#4-분산-트레이싱-오버헤드--sampling-10-vs-01)

---

## 1. FDS Kafka Streams 처리량 부하

### 배경

Transfer API가 이체를 생성하면 Kafka 토픽에 이벤트가 발행되고, FDS(Fraud Detection Service) Kafka Streams 애플리케이션이 이를 소비해 이상 거래를 탐지한다. 현재 `load-test.js`는 Transfer API의 HTTP 처리량만 측정하며, Kafka → FDS Streams 구간의 지연과 처리량은 측정하지 않는다.

### 목적

- Kafka Streams max throughput 측정 (메시지/초)
- Transfer API TPS 대비 FDS 처리 지연(consumer lag) 변화 추적
- 부하 증가 시 consumer lag이 누적되는 임계 TPS 탐색

### 시나리오 설계

**단계 1 — Transfer TPS 점진 증가**

```
10 TPS → 50 TPS → 100 TPS → 200 TPS
각 단계 2분 유지
```

**단계 2 — FDS consumer lag 모니터링**

매 10초마다 Kafka consumer group lag 수집:

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group fds-stream-group \
  | grep -E "TOPIC|LAG"
```

**측정 지표**

| 지표 | 측정 방법 |
|---|---|
| Consumer lag | `kafka-consumer-groups.sh` |
| Streams 처리 속도 | Kafka Streams JMX `records-consumed-rate` |
| FDS 탐지 응답 시간 | Micrometer Observation span 측정 |
| traceId 연속성 | Kibana에서 Transfer traceId로 FDS 로그 조인 |

**임계점 설정**

- consumer lag < 1,000 메시지 (10초 이내 처리)
- FDS 처리 지연 p95 < 500ms

**예상 병목**

- Kafka Streams 파티션 수 부족 → 병렬도 제한
- FDS 내 DB 조회(블랙리스트 등) 응답 시간

---

## 2. 일일 한도 캐시 비교 — Redis 도입 전·후

### 배경

이체 시 일일 한도를 확인하는 로직이 매 요청마다 DB에서 당일 누적 이체액을 집계한다. 이 집계 쿼리가 고부하 시 병목이 될 수 있으며, Redis에 캐싱하면 DB 조회를 제거할 수 있다.

### 목적

- Redis 미적용 상태에서 일일 한도 집계 쿼리의 DB 부하 측정
- Redis 도입 후 응답 시간·에러율 비교
- Redis 캐시 미스 시(첫 요청, 일자 변경) 처리 흐름 검증

### 시나리오 설계

**Before — DB 직접 조회**

```
VU: 50
기간: 2분
계좌 풀: 10개 (한도 확인 충돌 유도)
측정: http_req_duration, DB slow query log (> 100ms)
```

**After — Redis 캐시 적용**

```
VU: 50
기간: 2분
동일 계좌 풀
측정: http_req_duration, Redis hit rate (INFO stats keyspace_hits/keyspace_misses)
```

**캐시 미스 시나리오 별도 추가**

```
자정 직후 일자 변경 시뮬레이션:
- Redis 키 TTL을 1분으로 단축 설정
- TTL 만료 직후 동시 50 VU 요청 → cache stampede 발생 여부 확인
```

**측정 지표**

| 지표 | Before | After |
|---|---|---|
| avg 응답 시간 | DB 조회 포함 | Redis 조회만 |
| DB 쿼리 수 / 초 | 높음 | 낮음 |
| 에러율 | — | — |
| Redis hit rate | — | 95%+ 목표 |

**임계점 설정**

- p95 < 200ms (Redis 도입 후)
- Redis hit rate > 95%

---

## 3. ELK 로그 적재 한계 — Filebeat → Elasticsearch 지연

### 배경

커밋 d463d3a에서 ELK 스택(Filebeat → Logstash → Elasticsearch → Kibana)을 통합했다. 고부하 시 Transfer API가 대량의 구조화 로그를 출력하면 Filebeat → ES 파이프라인에 지연이 발생할 수 있다.

### 목적

- 고부하(100+ TPS) 시 Elasticsearch 인덱싱 지연 측정
- Filebeat 버퍼링 / 큐 백압력 발생 시점 탐색
- 로그 적재 지연이 Kibana 실시간 대시보드에 미치는 영향 관찰

### 시나리오 설계

**단계 1 — 점진적 TPS 증가**

```
k6로 10 → 50 → 100 TPS 단계별 부하
각 단계 3분 유지
```

**단계 2 — ES 인덱싱 지연 측정**

매 30초마다 수집:

```bash
# ES 인덱싱 지연 (pending tasks)
curl -s localhost:9200/_cluster/pending_tasks | jq '.tasks | length'

# Filebeat 처리량
curl -s localhost:5601/api/status | jq '.metrics.beats_stats'
```

**측정 지표**

| 지표 | 측정 방법 |
|---|---|
| ES pending indexing tasks | `_cluster/pending_tasks` |
| Filebeat output events/sec | Filebeat monitoring API |
| 로그 수신→Kibana 가시 지연 | 로그 생성 시간 vs Kibana `@timestamp` 차이 |
| ES heap 사용률 | `_nodes/stats/jvm` |

**임계점 설정**

- ES pending tasks < 100
- 로그 생성 → Kibana 가시 지연 < 30초

**예상 병목**

- ES 단일 노드로 인한 쓰기 처리량 한계 (기본 5,000~10,000 doc/s)
- Filebeat harvest → output 버퍼 크기 기본값(4096 이벤트) 초과 시 디스크 큐 전환

---

## 4. 분산 트레이싱 오버헤드 — sampling 1.0 vs 0.1

### 배경

커밋 3b37ee3에서 Micrometer Observation 기반 분산 트레이싱을 구현했다. `AsyncConfig`의 `ContextPropagatingTaskDecorator`로 비동기 스레드에도 traceId를 전파한다. sampling rate 1.0(100% 수집)은 개발/디버그 환경에서 유용하지만 프로덕션에서는 오버헤드가 된다.

### 목적

- sampling 1.0 vs 0.1 조건에서 Transfer API 응답 시간 차이 측정
- traceId 생성·전파·Zipkin/Tempo 전송 오버헤드 정량화
- 최적 sampling rate 권고값 도출

### 시나리오 설계

**조건 A — sampling rate 1.0 (100%)**

```yaml
management.tracing.sampling.probability: 1.0
```

**조건 B — sampling rate 0.1 (10%)**

```yaml
management.tracing.sampling.probability: 0.1
```

각 조건에서 동일 시나리오 실행:

```
k6 run load-test/load-test.js
(10 → 100 VU ramp-up, 9분 30초)
```

**측정 지표**

| 지표 | 조건 A | 조건 B |
|---|---|---|
| avg 응답 시간 | — | — |
| p95 응답 시간 | — | — |
| JVM CPU usage | — | — |
| Heap 사용량 | — | — |
| Zipkin/Tempo span 수신 속도 | 100% | 10% |

**임계점**

- 조건 A와 B의 p95 차이 < 20ms → sampling 1.0 유지 가능
- 차이 > 50ms → sampling 0.1 이하로 조정 권장

**추가 관찰 포인트**

- `@Async` 스레드 풀에서 traceId 전파 누락 여부 확인 (커밋 3b37ee3에서 수정한 내용)
- Kafka producer → consumer 구간 traceId 연속성 검증 (`bidirectional-test.js`와 병행)

---

## 우선순위 요약

| 우선순위 | 시나리오 | 이유 |
|---|---|---|
| 1 | 분산 트레이싱 오버헤드 | 프로덕션 sampling 설정 결정에 직접 영향 |
| 2 | FDS Kafka Streams 처리량 | 이미 구현된 Kafka 파이프라인 검증 |
| 3 | 일일 한도 캐시 비교 | Redis 도입 여부 의사결정 근거 |
| 4 | ELK 로그 적재 한계 | ELK 인프라 안정성 확인 |
