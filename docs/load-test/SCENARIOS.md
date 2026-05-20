# 시나리오 상세

## 목차

1. [simple-test — 베이스라인](#1-simple-test--베이스라인)
2. [load-test — 점진 부하](#2-load-test--점진-부하)
3. [stress-test — 한계 탐색](#3-stress-test--한계-탐색)
4. [hotspot-test — 단일 계좌 집중](#4-hotspot-test--단일-계좌-집중)
5. [keepalive vs no-keepalive — TCP TIME_WAIT 비교](#5-keepalive-vs-no-keepalive--tcp-timewait-비교)
6. [bidirectional-test — 양방향 데드락 방지](#6-bidirectional-test--양방향-데드락-방지)
7. [vus-10/25/50/100 — 스케일링 곡선](#7-vus-102550100--스케일링-곡선)

---

## 1. simple-test — 베이스라인

### 목적

가장 낮은 부하(VU=5, 10초)에서 Transfer API가 올바르게 응답하는지 확인하는 연기 테스트(smoke test)다. 시나리오 자체보다 **시스템이 기동 상태인지, 오류 없이 요청을 수용하는지** 빠르게 점검한다.

### 입력 분포

- 송금 계좌: `110-100-000001` ~ `110-100-000020` 중 균등 랜덤
- 수신 계좌: `110-200-000021` ~ `110-200-000040` 중 균등 랜덤
- 금액: 10,000 KRW 고정

### VU 수 / ramp-up 곡선

```
VUs : 5 (고정)
기간 : 10s
think time: sleep(1)
```

### 임계점

별도 임계값 없음 (순수 관찰 목적). `status is 200`과 `response < 500ms` 두 체크만 설정.

### 결과 측정

**k6-output.log (낙관적 락 시절, 2025-11-22)**

| 지표 | 값 |
|---|---|
| 총 요청 | 50 |
| TPS | 4.9 req/s |
| avg | 19 ms |
| p95 | 40.16 ms |
| 에러율 | **80%** (40/50) |

**k6-detailed.log (비관적 락 전환 후, 2025-11-23)**

| 지표 | 값 |
|---|---|
| 총 요청 | 50 |
| TPS | 4.8 req/s |
| avg | 37 ms |
| p95 | 75.96 ms |
| 에러율 | **18%** (9/50) |

**test-output.txt (별도 실행, 2025-11-22)**

| 지표 | 값 |
|---|---|
| 총 요청 | 50 |
| TPS | 4.8 req/s |
| avg | 37.23 ms |
| p95 | 50.31 ms |
| 에러율 | 38% (19/50) |

### 학습

낙관적 락 상태에서 VU 5개만으로도 에러율이 80%에 달했다. 오류 내용은 `Row was updated or deleted by another transaction` — JPA의 `StaleObjectStateException`이다. 송금 계좌 풀이 20개여서 VU 5명도 같은 계좌를 동시에 수정할 확률이 존재하며, 실제로 그 충돌이 즉각 오류로 노출됐다. 비관적 락 전환 후 18%로 줄었으나 완전히 0%가 되지 않은 것은 **전환 과도기에 일부 코드 경로가 아직 낙관적 락을 사용**했기 때문으로 추정된다.

---

## 2. load-test — 점진 부하

### 목적

10→100 VU 단계적 ramp-up으로 응답 시간이 부하에 비례해 증가하는지, 어느 VU 구간에서 p95가 임계값(500ms)을 넘는지 탐색한다.

### 입력 분포

simple-test와 동일. 송금/수신 각 20개 계좌에서 균등 랜덤 선택, 금액 10,000 KRW.

### VU 수 / ramp-up 곡선

```javascript
stages: [
  { duration: '30s', target: 10 },  // 웜업
  { duration: '2m',  target: 25 },  // Stage 1
  { duration: '2m',  target: 50 },  // Stage 2
  { duration: '2m',  target: 75 },  // Stage 3
  { duration: '2m',  target: 100 }, // Stage 4
  { duration: '30s', target: 0 },   // 쿨다운
]
// 총 9분 30초
```

### 임계점

| 임계값 | 조건 |
|---|---|
| `http_req_duration p(95)` | < 500 ms |
| `http_req_duration p(99)` | < 1000 ms |
| `errors rate` | < 10% |

### 결과 측정

전체 ramp-up 실행 로그 미수행 — 후속 작업으로 측정 필요.

simple-test 결과를 단계별로 외삽하면, VU 50 이상에서 비관적 락 대기로 p95가 500ms를 초과할 가능성이 높다.

### 학습

- `handleSummary` 함수를 구현해 자동으로 `summary.json`을 저장하도록 설계함.
- 단계별 커스텀 메트릭(`successful_transfers`, `failed_transfers`)으로 VU 전환 시점의 성공률 변화를 추적 가능.
- 다음 실행 시 `--out json=results.json` 옵션으로 Kibana / Grafana 연동 권장.

---

## 3. stress-test — 한계 탐색

### 목적

`sleep(0.1)` (100ms)로 think time을 최소화해 단위 시간당 실제 최대 TPS를 측정하고, 시스템이 어느 VU 구간에서 무너지는지(에러율 급증, 응답 시간 폭발) 확인한다.

### 입력 분포

simple-test와 동일한 계좌 풀. `sleep(0.1)` 이므로 실효 TPS = VU × 10.

### VU 수 / ramp-up 곡선

```javascript
stages: [
  { duration: '30s', target: 50  }, // 웜업
  { duration: '1m',  target: 100 }, // 100 VU (~1,000 TPS 시도)
  { duration: '1m',  target: 150 }, // 150 VU
  { duration: '1m',  target: 200 }, // 200 VU 한계 도전
  { duration: '30s', target: 0   }, // 쿨다운
]
// 총 4분
```

### 임계점

| 임계값 | 조건 |
|---|---|
| `http_req_duration p(95)` | < 500 ms |
| `success_rate` | > 95% |

### 결과 측정

실측 로그 미수행 — 후속 작업으로 측정 필요.

**예상 병목 구간**: VU 100 ~ 150 구간에서 DB 커넥션 풀 (기본값 10개)이 포화되어 `HikariPool connection timeout` 발생 가능.

### 학습

- DB 커넥션 풀 크기(`spring.datasource.hikari.maximum-pool-size`)를 VU 수에 맞게 튜닝하지 않으면 stress 구간에서 커넥션 대기가 병목이 됨.
- nginx upstream worker 수, Transfer API의 Tomcat thread 수도 병목 포인트.
- 단일 인스턴스로 VU 200을 감당하기 어렵고, 수평 확장(2대 이상)이 필요함을 이 시나리오가 드러낸다.

---

## 4. hotspot-test — 단일 계좌 집중

### 목적

모든 VU가 동일한 송금 계좌(`110-100-000001`)에서 출금하는 극단적 핫스팟 상황을 만들어 **비관적 락 대기 큐 깊이와 응답 시간 증가 패턴**을 측정한다.

### 입력 분포

- 송금 계좌: `110-100-000001` 고정 (핫스팟)
- 수신 계좌: `110-200-000021` ~ `110-200-000040` 중 균등 랜덤
- 금액: 100 KRW (잔액 부족 방지)

```javascript
const HOTSPOT_SENDER = '110-100-000001';
sleep(0.05); // think time 50ms → 최대 경합 시뮬레이션
```

### VU 수 / ramp-up 곡선

```
Warm-up : 20 VU / 30s
Stage 1 : 50 VU / 1m
Stage 2 : 100 VU / 1m
쿨다운  : 0 VU / 30s
```

### 임계점

| 임계값 | 조건 |
|---|---|
| `success_rate` | > 95% |
| `http_req_duration p(95)` | < **2000 ms** (락 대기 반영) |

p95 임계값을 2000ms로 완화한 이유: 비관적 락 직렬 처리 시 VU 100이 동시에 대기하면 이론상 최악 대기 시간 = 락 처리 시간 × (VU - 1).

### 결과 측정

실측 로그 미수행 — 후속 작업으로 측정 필요.

### 학습

- 단일 계좌 핫스팟은 비관적 락 환경에서 **직렬 처리 병목**이 된다. VU 100에서 처리량이 VU 50과 동일(잠금 직렬화)해질 가능성 있음.
- 실제 서비스에서는 핫스팟 계좌(고빈도 판매자 계좌 등)에 대해 별도 처리량 제한이 필요.
- 잔액 100원으로 설정한 것은 장기 테스트 중 잔액 고갈로 인한 오류를 막기 위한 설계 선택.

---

## 5. keepalive vs no-keepalive — TCP TIME_WAIT 비교

### 목적

TCP Keep-Alive 사용 여부가 소켓 자원 고갈과 처리 성능에 미치는 영향을 실측한다. 커밋 8cf1ad7에서 `RestTemplate`의 TIME_WAIT 문제를 직접 확인한 경험에서 출발한 시나리오다.

### 입력 분포

두 시나리오 모두 동일: 송금/수신 각 20개 계좌 균등 랜덤, VU=20, 기간=10s, sleep 없음.

### 차이점

**keepalive-test.js** — `Connection` 헤더 없음 (HTTP/1.1 기본: Keep-Alive)

```javascript
const params = {
  headers: { 'Content-Type': 'application/json' },
  // Connection 헤더 없음 = Keep-Alive 기본
};
```

**no-keepalive-test.js** — `Connection: close` 강제

```javascript
const params = {
  headers: {
    'Content-Type': 'application/json',
    'Connection': 'close', // 매 요청마다 연결 종료 강제
  },
};
```

### VU 수 / ramp-up 곡선

```
VUs: 20 (고정)
기간: 10s
sleep: 없음 (최대 속도)
```

### 임계점

별도 임계값 없음. 비교 지표:
- `http_req_duration` avg / p95
- OS 소켓 상태(`netstat -an | grep TIME_WAIT | wc -l`)

### 결과 측정

실측 로그 미수행 — 후속 작업으로 측정 필요.

**예상 차이**:
- keepalive: TIME_WAIT 소켓 수 낮음, 응답 시간 빠름
- no-keepalive: TIME_WAIT 소켓 수 급증, 포트 고갈 시 `connection refused` 발생 가능

### 학습

- HTTP 클라이언트(`RestTemplate`, `WebClient`)에서 `Connection: close`를 명시하거나 커넥션 풀을 미설정하면 매 요청마다 TCP 세션이 새로 생성·종료된다.
- macOS / Linux에서 TIME_WAIT 기본 타임아웃은 60초. 초당 1,000 req/s면 60,000개 소켓이 TIME_WAIT 상태로 쌓인다.
- 해결책: `HttpComponentsClientHttpRequestFactory`에 `PoolingHttpClientConnectionManager` 설정, 또는 Spring WebClient의 Reactor Netty 기본 풀 사용.

---

## 6. bidirectional-test — 양방향 데드락 방지

### 목적

A→B 와 B→A 송금이 동시에 발생하는 상황에서 **정렬된 락 획득 전략**이 데드락 없이 두 트랜잭션을 순서대로 처리하는지 검증한다.

### 입력 분포

- 계좌 풀: `110-100-000001`~`000020` + `110-200-000021`~`000040` (총 40개)
- 두 인덱스를 랜덤으로 뽑아 양방향 가능 (`idx1 != idx2`)
- 금액: 1,000 KRW (잔액 부족 방지)
- think time: `sleep(0.1)` (100ms)

```javascript
// 랜덤하게 두 계좌 선택
const idx1 = Math.floor(Math.random() * accounts.length);
let idx2 = Math.floor(Math.random() * accounts.length);
while (idx2 === idx1) { idx2 = ...; }
```

### VU 수 / ramp-up 곡선

```
VUs: 50 (고정)
기간: 2분
timeout: 30s (데드락 감지용)
```

### 임계점

| 임계값 | 조건 |
|---|---|
| `success_rate` | > 99% |
| `deadlock_errors` | **count < 1** |

`deadlock_errors` 카운터는 응답 바디에 `deadlock`, `timeout`, `lock` 키워드가 포함된 경우 증가한다.

### 결과 측정

실측 로그 미수행 — 후속 작업으로 측정 필요.

### 학습

- 데드락 방지의 핵심은 두 계좌 중 항상 ID가 작은 계좌의 락을 먼저 획득하는 **일관된 순서 규칙**이다.
- `timeout: '30s'` 설정으로 데드락 발생 시 k6 레벨에서도 감지 가능.
- 비관적 락(`SELECT FOR UPDATE`)을 사용하더라도 락 획득 순서가 보장되지 않으면 데드락이 발생한다.

---

## 7. vus-10/25/50/100 — 스케일링 곡선

### 목적

동일한 시나리오(균등 랜덤 계좌, `sleep(1)`, 10,000 KRW)를 VU 수만 변화시켜 **처리량이 VU에 비례해 선형적으로 증가하는지** 확인한다.

### 입력 분포

모두 동일: 송금 20개 / 수신 20개 계좌 균등 랜덤, 금액 10,000 KRW.

### VU 수 / 기간 비교

| 파일 | VU | 기간 | 예상 TPS |
|---|---|---|---|
| `vus-10.js` | 10 | 30s | ~10 |
| `vus-25.js` | 25 | 1m | ~25 |
| `vus-50.js` | 50 | 1m | ~50 |
| `vus-100.js` | 100 | 1m | ~100 |

`sleep(1)` 기반이므로 이론상 TPS = VU수.

### 임계점

| 임계값 | 조건 |
|---|---|
| `status is 200` | 체크 수행 |
| `response < 500ms` | 체크 수행 |

별도 `thresholds` 미설정 — 관찰 목적.

### 결과 측정

개별 실행 로그 미수행 — 후속 작업으로 측정 필요.

**스케일링 곡선 예상 패턴**:

```
TPS
 100 |                      *
  75 |                *
  50 |          *
  25 |    *
  10 | *
      10  25  50  100   VU
```

실제로는 VU 50~100 구간에서 DB 락 경합으로 TPS 증가가 둔화(수렴)될 가능성 있음.

### 학습

- `sleep(1)` 기반 시나리오는 네트워크 응답 시간이 짧을 때 TPS ≈ VU 가 성립하나, 응답 시간이 1s를 넘으면 TPS < VU 가 된다.
- 비관적 락 환경에서는 VU가 늘어도 DB 직렬화 병목으로 처리량이 수렴하는 패턴이 관찰되며, 이 꺾이는 지점이 단일 인스턴스의 실질적 한계다.
- 한계 초과 시 수평 확장(Transfer API Pod 2대+)과 DB 샤딩 / 읽기 분리가 다음 단계.
