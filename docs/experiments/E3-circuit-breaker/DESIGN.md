# E3 — Redis Circuit Breaker chaos 실험 설계서

> **Status**: 설계 단계. 실험 미수행. 측정값은 본 문서에 포함되지 않는다.
> **목적**: Resilience4j 기반 Circuit Breaker(CB) 가 Redis 장애 상황에서 송금 API 의
> 응답 시간 폭증을 막아주는지 정량 검증하기 위한 실험을 설계한다.
> **선행 코드**: 커밋 `09f40ab` — `RedisDailyTransferAmountCacheAdapter` 에
> `@CircuitBreaker` + `@Retry` + fallback 도입.

---

## 1. 배경

### 1.1 왜 이 실험이 필요한가

Redis 는 일일 송금 한도 누적치를 보관하는 캐시다. 송금 API 의 검증 경로에서
`getTodayAmount` / `addTodayAmount` 가 **동기 호출**된다. 즉 Redis 응답이 느려지면
송금 API 응답 시간도 그대로 따라 느려진다.

운영 환경의 Redis 는 단일 노드다 (`docs/LIMITATIONS.md` 1.2). 즉 다음 3가지가
동시에 사실이다.

- Redis 가 송금 API 의 동기 의존성이다.
- Redis 는 단일 노드라 언제든 죽을 수 있다.
- 그 영향을 코드 레벨에서 막기 위해 CB 를 도입했다.

문제는 "CB 를 붙였다" 가 "CB 가 실제로 효과를 낸다" 와 같은 말이 아니라는 점이다.
설정값 (sliding-window=20, failure-rate=50%, wait=10s) 이 실제 장애 패턴에서
의도대로 동작하는지는 chaos 실험 없이는 확인되지 않는다.

### 1.2 CB 설정 요약 (현재 적용값)

`services/transfer/instances/api/src/main/resources/application.yml`

| 항목 | 값 | 의미 |
|---|---|---|
| sliding-window-type | COUNT_BASED | 호출 수 기반 |
| sliding-window-size | 20 | 최근 20회 호출 기준 |
| minimum-number-of-calls | 10 | 10회 이상 모여야 평가 |
| failure-rate-threshold | 50% | 실패율 50% 초과 시 OPEN |
| wait-duration-in-open-state | 10s | OPEN 유지 시간 |
| permitted-number-of-calls-in-half-open-state | 3 | HALF_OPEN 에서 3회 시도 |
| record-exceptions | RedisConnectionFailureException, QueryTimeoutException, RedisCommandTimeoutException, RedisException | 이 예외만 CB 카운트 |

Retry 는 `max-attempts: 2`, `wait-duration: 100ms` 로 설정되어 있다.
즉 1회 실패하면 100ms 뒤 1회 재시도 후 fallback.

### 1.3 fail-open 정책의 트레이드오프

`getTodayAmountFallback` 은 `0L` 을 반환한다. 즉 Redis 가 죽으면
**모든 사용자의 오늘 누적 송금액이 0으로 보인다**. 일일 한도 검증은 통과시켜야
하는데, 누적치를 모르므로 검증을 사실상 무력화하는 쪽 (over-permissive) 을 택했다.

반대 선택(fail-closed) 은 Redis 가 죽으면 송금을 거절한다. 안전하지만 가용성이
떨어진다. 본 실험에서는 현재 정책 (fail-open) 의 정량적 비용도 함께 측정한다.

---

## 2. 가설

각 가설은 **chaos 시나리오 + 측정 지표 + 기대 임계값** 의 묶음이다.

### H1 — 베이스라인 폭증

> CB 가 없는(또는 OPEN 되지 않은) 상태에서 Redis 응답이 timeout 까지 늘어지면,
> 송금 API p95 는 10초를 넘는다.

근거: Lettuce 의 명령 timeout 기본값 + Retry `max-attempts: 2` 누적.
1차 호출이 timeout 까지 대기 → Retry 100ms wait → 2차 호출도 timeout.
대략 `(timeout × 2) + 100ms` 가 단일 송금 호출의 한도.

측정: `http_server_requests_seconds{uri="/api/transfers"}` p95.
기대: ≥ 10s. (Lettuce default 명령 타임아웃 5s 가정 시 2회 누적 ~10s 이상)

### H2 — CB 적용 후 폭증 차단

> CB 가 OPEN 상태일 때, 송금 API p95 는 200ms 이하로 유지된다.

근거: OPEN 상태에서는 Redis 호출이 즉시 fallback 으로 분기된다.
즉 Redis 라운드트립도, Retry 대기도 발생하지 않는다.
송금 경로 전체의 다른 지연(DB 트랜잭션, 검증 로직) 만 남는다.

측정: `http_server_requests_seconds{uri="/api/transfers"}` p95.
기대: ≤ 200ms. (CB CLOSED 정상 상태와 거의 동일해야 한다)

### H3 — 회복 시간

> Redis 가 복구된 시점부터 CB 가 CLOSED 로 돌아오기까지 30초 이내.

근거:
- `wait-duration-in-open-state: 10s` → OPEN 유지 10초
- `automatic-transition-from-open-to-half-open-enabled: true` → 자동 전이
- HALF_OPEN 에서 `permitted-number-of-calls-in-half-open-state: 3` 회 시도
- 3회 모두 성공이면 CLOSED

실제 측정 가능 시간 = `10s (OPEN 대기) + HALF_OPEN 시도 시간` ≈ 10~15s 예상.
기대: ≤ 30s (보수적 상한).

측정: `resilience4j_circuitbreaker_state{name="redis"}` 의 상태 전이 시각 차이.

### H4 — 트레이드오프 측정 가능성 (fail-open 비용)

> CB 가 OPEN 인 동안 발생한 송금 요청 중, **fallback 으로 인해 일일 한도
> 검증이 우회된 건수**가 메트릭으로 식별 가능해야 한다.

이 가설은 "측정값이 어떻게 나올 것이다" 가 아니라 **측정 가능성 자체**를
검증하는 가설이다. fail-open 의 비용을 사후 회계 정정으로라도 추적하려면,
"OPEN 동안 통과된 송금" 을 분리해서 셀 수 있어야 한다.

측정 지표:
- `redis_daily_limit_fallback_total` — fallback 호출 횟수
- `http_server_requests_seconds_count{uri="/api/transfers", status="200"}` 중
  CB OPEN 시점과 겹치는 구간

기대: 두 메트릭으로 OPEN 구간의 성공 송금 = fail-open 통과 건수를
근사 가능해야 한다. (정확한 회계 검증은 후속 작업 — RETRO 참조)

---

## 3. 실험 도구

### 3.1 Toxiproxy — Redis 앞단 네트워크 제어

Shopify 의 TCP 프록시. Redis 와 애플리케이션 사이에 끼워서 다음을 주입한다.

- `latency` — N ms 추가 지연
- `timeout` — 일정 확률로 연결 끊기
- `bandwidth` — 대역폭 제한
- `slow_close` / `slicer` — 부분 응답

장점: Redis 컨테이너 자체는 정상이라 회복 시점을 정확히 통제 가능.
단점: 별도 컨테이너 추가, application config 에서 Redis 호스트 변경 필요.

### 3.2 Pumba — 컨테이너 임의 종료

`pumba kill -s SIGKILL redis` 로 Redis 컨테이너 자체를 죽인다.
Toxiproxy 가 네트워크 계층 시뮬레이션이라면, Pumba 는 **프로세스 사망** 을 본다.

### 3.3 k6 — 부하 + RT 측정

기존 `docs/load-test/` 의 k6 스크립트를 재사용. 송금 API 에 일정 RPS 부하를
주면서 chaos 가 진행되는 동안의 응답 분포를 수집한다.

### 3.4 측정 스택

이미 구축된 것을 그대로 쓴다.
- Prometheus — Resilience4j / Micrometer / Spring 메트릭 수집
- Grafana — CB 상태 시각화
- Alertmanager — `RedisCircuitBreakerOpen` 알림 발화 확인

---

## 4. Chaos 시나리오 매트릭스

| ID | 시나리오 | 조작 | 기대 CB 상태 | 검증 가설 |
|---|---|---|---|---|
| C1 | 베이스라인 | Toxiproxy pass-through | CLOSED 유지 | H2 의 기준값 확보 |
| C2 | 경계 부하 | Redis 응답 +500ms | CLOSED 유지 (warning) | failure-rate 임계 미만 동작 |
| C3 | 명확한 장애 | Redis 응답 +2s (timeout 유발) | CLOSED → OPEN | H1, H2 |
| C4 | 영구 장애 | `pumba kill -s SIGKILL redis` | OPEN 유지 | H2 (장기) |
| C5 | 회복 | Redis 정상 → C3 → 정상 | CLOSED → OPEN → HALF_OPEN → CLOSED | H3 |

각 시나리오는 다음 3 구간으로 분할 측정한다.

1. **준비 (warm-up)** — k6 정상 부하 60초. 메트릭 베이스 수집.
2. **주입 (injection)** — chaos 시작. 120초 유지.
3. **회복 (recovery)** — chaos 해제. 60초 추가 관찰.

### C5 상세 — 회복 시간 측정

회복 시간 측정은 단순한 "장애 → 복구" 가 아니라 다음 3개의 시각을 따로 잡는다.

- `t_open` — CB 가 OPEN 으로 전이된 시각
- `t_redis_ok` — Redis 가 다시 정상이 된 시각 (Toxiproxy disable 시각)
- `t_closed` — CB 가 CLOSED 로 돌아온 시각

H3 의 "회복 시간" 은 `t_closed - t_redis_ok` 로 정의한다.
`t_closed - t_open` 은 CB 가 회복을 *시도*하기까지의 OPEN 유지 시간이므로 다르다.

---

## 5. 측정 지표

### 5.1 Prometheus 메트릭

| 메트릭 | 의미 | 사용처 |
|---|---|---|
| `resilience4j_circuitbreaker_state{name="redis", state=...}` | CB 상태 (0/1) | H3 회복 시간 |
| `resilience4j_circuitbreaker_calls_total{name="redis", kind=...}` | 성공/실패/거부 횟수 | failure-rate 추세 |
| `redis_daily_limit_fallback_total` | fallback 호출 누적 | H4 측정 가능성 |
| `http_server_requests_seconds{uri="/api/transfers"}` p95/p99 | 송금 API 응답시간 | H1, H2 |
| `http_server_requests_seconds_count{status=~"2..|5.."}` | 송금 성공/실패율 | 운영 영향 |
| `lettuce_command_completion_seconds` | Redis 명령 RT | 장애 주입 검증 |

### 5.2 로그 검증

- 애플리케이션 로그의 `[redis-fallback]` 라인 수가 fallback counter 와 일치하는지
- `RedisConnectionFailureException` / `RedisCommandTimeoutException` 발생 분포
- `RedisCircuitBreakerOpen` alert 가 30초 이상 OPEN 시점에 실제로 발화하는지

---

## 6. 통제 변수

실험 결과가 chaos 주입 외 요인 때문이 아니라는 것을 보이기 위해 고정한다.

- **단일 인스턴스 송금 API** (멀티 인스턴스 변수 제거)
- **k6 부하 패턴** — 50 VU, 일정 RPS, 동일 사용자 풀
- **JVM warm-up** — 준비 구간에서 GC/JIT 영향 흡수
- **DB / Kafka 정상** — Redis 외 의존성은 정상
- **시계 동기화** — 컨테이너 NTP, 측정 시각 기준 통일
- **로깅 레벨** — `WARN` 고정 (DEBUG 가 응답시간에 미치는 영향 제거)

---

## 7. 성공 / 실패 기준

가설별로 명시한다. "어떤 값이 나오면 가설이 깨진 것인가" 까지 정한다.

| 가설 | 성공 기준 | 실패(가설 기각) 기준 |
|---|---|---|
| H1 | C3 / C4 에서 CB 미적용 시뮬레이션의 p95 ≥ 10s | p95 < 5s — Redis timeout 설정이 예상보다 짧다는 뜻 |
| H2 | C3 / C4 의 OPEN 구간 p95 ≤ 200ms | p95 > 500ms — fallback 자체에 비용이 있다는 뜻, 코드 점검 필요 |
| H3 | C5 의 `t_closed - t_redis_ok` ≤ 30s | > 30s — HALF_OPEN 의 시도 실패 시 OPEN 으로 되돌아갔을 가능성 |
| H4 | OPEN 구간의 fallback 카운터 ≥ 송금 시도 횟수 | 카운터 누락 — 메트릭 노출 누락, 코드 수정 필요 |

H1 의 "CB 미적용 시뮬레이션" 은 두 가지 방식 중 하나.
- 어노테이션 임시 제거 + 별도 빌드
- `failure-rate-threshold: 100` 으로 두어 사실상 OPEN 불가능하게 만든 컨피그

후자가 코드 변경 없이 가능하므로 선택지로 우선한다.

---

## 8. 실험 범위 밖 (Out of Scope)

이 실험에서 다루지 않는 것을 명시해서 결과 해석의 범위를 좁힌다.

- **Redis Cluster / Sentinel** — 단일 노드 가정. 클러스터의 partial failure 는 별도 실험.
- **fail-closed 정책 비교** — RETRO 의 후속 실험(E3-2)으로 분리.
- **Bulkhead** — 현재 미적용. 다른 어댑터가 Redis CB 의 영향을 받는지는 E3-3 에서.
- **장기 운영 (수 시간 이상)** — chaos 5분 시나리오 기준. 메모리 누수, GC 누적은 별도.
- **멀티 인스턴스 송금 API 의 CB 상태 일관성** — Resilience4j 는 인스턴스 로컬 상태.
  각 인스턴스가 독립적으로 OPEN/CLOSED 를 가진다. 이 실험에서는 단일 인스턴스 기준.

---

## 9. 다음 단계

1. Toxiproxy / Pumba 를 docker-compose 의 실험용 profile 로 분리.
2. Application config 의 Redis host 를 Toxiproxy 경유로 변경하는 spring profile (`chaos`) 추가.
3. k6 스크립트에 chaos 트리거 hook 추가 (시나리오 진입 시점 정렬).
4. Grafana 에 본 실험 전용 dashboard 추가 (CB state, fallback rate, API p95 한 화면).
5. C1 부터 순차 실행, 각 시나리오 결과를 `RESULTS.md` 에 추가.

본 문서가 작성된 시점에는 1번부터 막혀 있다. 그 이유와 미수행의 갭은
[RESULTS.md](RESULTS.md) 와 [RETRO.md](RETRO.md) 에서 설명한다.
