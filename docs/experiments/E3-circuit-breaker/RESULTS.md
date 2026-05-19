# E3 — Results

> **Status: NOT YET RUN**
>
> 이 문서는 측정값 보고서가 아니다. 실험이 아직 실행되지 않은 상태에서,
> (1) DESIGN.md 의 가설별 **예상 결과**, (2) 미수행 사유, (3) 코드/테스트 레벨에서
> 부분적으로 확보한 근거를 정리한다. 실측이 들어오면 같은 표 위에 실제 값을 덮어쓴다.

---

## 1. 현재 상태

| 항목 | 상태 |
|---|---|
| Toxiproxy 도입 | 미적용 |
| Pumba 도입 | 미적용 |
| k6 chaos 시나리오 스크립트 | 미작성 |
| Grafana 실험 dashboard | 미생성 |
| 실측 데이터 | **없음** |
| 단위/통합 테스트로 부분 검증한 항목 | 일부 (아래 4장) |

본 문서의 모든 수치는 **추정** 이다. 실측이 아니다.

---

## 2. 예상 결과 매트릭스 (가설 기반 추정)

### 2.1 H1 — CB 미적용(또는 failure-rate=100) 베이스라인

| 시나리오 | 송금 API p95 (추정) | 송금 API 성공률 (추정) | 근거 |
|---|---|---|---|
| C1 (정상) | 50~150 ms | 100% | 일반 부하 테스트 경험 |
| C2 (+500ms) | 1.1~1.3 s | 100% | (Redis 500ms × 2 retry) + 본 처리 |
| C3 (+2s, timeout) | **10~12 s** | < 50% | Lettuce default timeout 5s × 2 retry 누적 |
| C4 (kill -9) | 5~10 s 후 실패 | 0% | 연결 자체 실패, retry 후 예외 |

H1 가설의 핵심: C3, C4 에서 **사용자가 송금 화면에서 10초 이상 멈춤** 을 본다는 것.
즉 CB 없이는 단일 의존성의 장애가 그대로 사용자 응답시간으로 전파된다.

### 2.2 H2 — CB 적용 후

| 시나리오 | 송금 API p95 (추정) | CB 상태 변화 (추정) |
|---|---|---|
| C1 | 50~150 ms | CLOSED 유지 |
| C2 | 600~800 ms | CLOSED (failure-rate < 50%) |
| C3 | **OPEN 직전 1~2s 스파이크 → OPEN 이후 100~200 ms** | CLOSED → OPEN 약 1~3초 내 |
| C4 | OPEN 진입 후 100~200 ms 유지 | OPEN 고정 (Redis 회복 불가) |

C3, C4 의 핵심: OPEN 진입 *전* 의 짧은 스파이크가 있다.
sliding-window-size=20 + minimum-number-of-calls=10 이므로, OPEN 으로 가기 전
**최소 10번 이상의 실패가 필요**하다. 이 동안의 요청은 timeout 비용을 그대로 본다.
즉 CB 가 "장애 시작 직후 부터 차단" 하는 것이 아니라, "장애 임계를 넘으면 차단"
한다는 의미.

### 2.3 H3 — 회복 시간

| 측정점 | 추정 값 |
|---|---|
| `t_open` → `t_redis_ok` | 시나리오 정의에 따라 (실험자 통제) |
| `t_redis_ok` → `t_half_open` | 0~10 s (남은 wait-duration) |
| `t_half_open` → `t_closed` | 짧음. HALF_OPEN 에서 3회 호출 × 평균 RT |
| **전체 `t_closed - t_redis_ok`** | **10~15 s (추정)** |

`wait-duration-in-open-state: 10s` 가 회복 시간의 하한이다.
즉 Redis 가 OPEN 진입 직후 회복돼도 최소 10초는 OPEN 을 유지한다.
이건 "장애가 끝났는데도 송금이 안 된다" 가 아니라 "10초 동안은 fallback 으로 계속 처리"
다. fail-open 정책 하에서는 사용자 체감 장애가 아니지만, fail-closed 로 정책 변경 시
이 10초가 그대로 송금 거절 시간이 된다.

### 2.4 H4 — fail-open 비용의 측정 가능성

다음 두 메트릭이 동시에 존재함을 코드 레벨로 확인한다.

- `redis_daily_limit_fallback_total` — `RedisDailyTransferAmountCacheAdapter` 의
  `fallbackCounter` 가 fallback 메서드에서 `increment()` 됨 (코드 확인).
- `resilience4j_circuitbreaker_state{name="redis"}` — Resilience4j 가 Micrometer
  로 자동 노출 (`register-health-indicator: true`).

따라서 **메트릭 노출은 가능** 으로 추정. 하지만 다음은 미검증.

- OPEN 동안의 송금 성공 건수와 fallback 카운터가 1:1 매핑되는지 (송금당 fallback
  호출이 1회인지 2회인지 — `getTodayAmount` + `addTodayAmount` 양쪽 모두 fallback
  으로 빠지면 2회 카운트).
- 회계 정정에 필요한 "어떤 사용자가 OPEN 구간에 한도를 초과해 송금했는가" 의
  사후 추적은 메트릭이 아니라 `transactions` 테이블의 created_at + Redis 장애 구간
  교차로 해야 함. 메트릭만으로는 안 된다.

---

## 3. 실험 미수행 사유

### 3.1 chaos 인프라 부재

가장 큰 단일 사유. 본 프로젝트의 docker-compose 에는 다음이 없다.

- **Toxiproxy 컨테이너** — Redis 앞단의 프록시 레이어가 없으므로, latency / timeout
  주입을 코드 변경 없이 할 방법이 없다.
- **chaos profile** — spring profile 단에서 Redis host 를 Toxiproxy 로 swap 하는
  설정이 없다.
- **Pumba** — 컨테이너 임의 종료는 가능하지만 (`docker kill`), 그 시점과 k6 부하
  주입 시점을 정렬하는 스크립트가 없어 측정 정확도를 보장 못 한다.

이 인프라를 도입하는 것은 별도의 작은 PR 로 분리하는 게 맞다고 판단했다.
설계만 먼저 굳히고, 인프라는 다음 단계.

### 3.2 우선순위 결정

같은 시간을 송금 도메인의 다른 부분에 썼다.

- E2 (locking strategy) — 비관 락 도입 후 데드락/RT 측정
- 분산 트레이싱 컨텍스트 전파 (`3b37ee3`)
- ELK 통합 (`d463d3a`)

E3 의 chaos 실험은 운영 위험을 *측정* 하기 위한 작업이지, 운영 위험을 *제거*
하는 작업이 아니다. CB 코드 자체는 이미 들어가 있다. 따라서 측정의 우선순위가
구현보다 뒤로 밀렸다. 이 판단이 옳았는지는 [RETRO.md](RETRO.md) 에서 다룬다.

### 3.3 측정 환경의 한계

설사 chaos 도구를 붙여도 다음 한계 때문에 결과의 외부 타당성이 약하다.

- 로컬 단일 머신 측정 → 운영 클러스터의 네트워크 RTT 와 다름.
- 단일 인스턴스 송금 API → 다중 인스턴스의 CB 상태 독립성 미반영.
- 단일 Redis → 단일 노드 장애만 본다. Cluster partial failure 와 무관.

즉 실험을 돌려도 결과는 "이 환경에서는 이렇더라" 수준이지,
"운영에서도 그럴 것이다" 까지 가지는 않는다. 이 점은 결과 해석 시 명시한다.

---

## 4. 대안 검증 — 코드/테스트로 부분 확인된 것

실측이 없어도 다음은 확인된다. **단, 이건 chaos 실험의 대체가 아니다.**
코드가 그렇게 *생겼다* 와 운영에서 그렇게 *작동한다* 는 다른 문제다.

### 4.1 record-exceptions 매핑 확인 (코드 리뷰)

`application.yml` 의 `record-exceptions` 에 다음 4개가 등재되어 있다.

- `org.springframework.data.redis.RedisConnectionFailureException`
- `org.springframework.dao.QueryTimeoutException`
- `io.lettuce.core.RedisCommandTimeoutException`
- `io.lettuce.core.RedisException`

Lettuce 가 명령 timeout 시 `RedisCommandTimeoutException` 을 던지는 것은 라이브러리
계약이다. 즉 timeout 발생 → CB 카운터 증가 경로는 라이브러리 + 설정 레벨에서 닫혀 있다.

미확인: 실제 Lettuce 가 어떤 시점에 어떤 예외를 던지는지 (timeout 도중 / 연결 끊김 /
부분 응답). 이건 chaos 없이는 알 수 없다.

### 4.2 fallback 카운터 증가 (통합 테스트로 부분 확인)

`RedisDailyTransferAmountCacheAdapterIntegrationTest` 는 Testcontainers Redis 정상
상태만 검증한다. **fallback 경로는 테스트 안 됨.**

따라서 다음 테스트를 추가하면 H4 의 측정 가능성을 단위 레벨에서 확보 가능.

- Mock `StringRedisTemplate` 가 `RedisConnectionFailureException` 을 던지도록 설정.
- `getTodayAmount(userId)` 호출 → fallback 메서드 호출 확인.
- `meterRegistry.get("redis.daily_limit.fallback").counter().count()` 가 1 증가.

이 테스트는 **본 PR 이후 추가 작업** 으로 둔다.

### 4.3 Prometheus alert 의 표현식 검증 (정적)

`monitoring/prometheus-alerts.yml` 의 `RedisCircuitBreakerOpen` 알림 표현식:

```promql
resilience4j_circuitbreaker_state{name="redis", state="open"} == 1
for: 30s
```

표현식 자체는 Resilience4j 가 노출하는 메트릭 형태와 일치한다 (정적 확인).
`for: 30s` 는 OPEN 진입 후 30초 이상 유지될 때만 발화. C3 / C4 시나리오에서는
발화 예상, C5 (10~15s OPEN 후 회복) 시나리오에서는 미발화 예상.

이 동작이 의도한 운영 정책인지는 별도 논의가 필요하다. 짧은 OPEN 도 알람을 받고
싶다면 `for` 를 줄여야 한다.

---

## 5. 미수행 갭이 위험인 이유

CB 가 "코드에 들어가 있다" 와 "운영에서 의도대로 작동한다" 사이의 거리는 다음과
같이 좁히지 못한 상태다.

| 위험 항목 | 현재 상태 | LIMITATIONS 연결 |
|---|---|---|
| H1 (베이스라인 폭증) 실측 | 추정만 있음. p95 가 정확히 얼마인지 모름 | LIMITATIONS 1.2 (Redis 단일 노드) |
| H2 (OPEN 후 차단) 실측 | 추정. fallback 자체의 비용 미측정 | FAILURE-MODES 1.4 |
| H3 (회복 시간) 실측 | 설정값 기반 추정 (10~15s). HALF_OPEN 실패 케이스 미검증 | — |
| H4 (fail-open 비용 추적) | 메트릭 노출은 코드로 확인. 회계 정정 절차 부재 | LIMITATIONS 1.2, FAILURE-MODES 1.4 |

**가장 큰 운영 갭**: Redis 가 죽었을 때 fail-open 으로 통과한 송금의 회계 정정
SOP 가 없다. 메트릭으로 "통과했다" 까지는 보이지만, "어느 거래가 한도 위반이었는지"
는 `transactions` 테이블과 Redis 장애 구간을 사후 join 해야 한다. 이 절차가 문서화
되어 있지 않다.

이 갭은 chaos 실험 없이도 알 수 있는 갭이고, 본 실험과 별개로 운영 절차 문서화가
필요하다.

---

## 6. 결론

- 본 실험은 **설계 완료, 측정 미실행** 상태다.
- 모든 결과 수치는 추정이며, 실측으로 대체되어야 한다.
- 부분 검증된 것은 (a) record-exceptions 매핑, (b) 메트릭 노출 가능성, (c) alert
  표현식의 형태 정합성. 이 셋은 chaos 실험을 *대체* 하지 않는다.
- 후속 작업은 [RETRO.md](RETRO.md) 의 E3-1 ~ E3-3.
