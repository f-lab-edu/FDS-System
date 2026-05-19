# 부하 테스트 개요

## 목차

1. [왜 k6인가](#1-왜-k6인가)
2. [테스트 환경](#2-테스트-환경)
3. [가설](#3-가설)
4. [시나리오 목록](#4-시나리오-목록)
5. [실행 방법](#5-실행-방법)
6. [결과 요약](#6-결과-요약)
7. [주요 회고](#7-주요-회고)

---

## 1. 왜 k6인가

| 항목 | k6 | JMeter | Gatling |
|---|---|---|---|
| 스크립트 언어 | JavaScript (ES6+) | XML / GUI | Scala DSL |
| 러닝 커브 | 낮음 | 중간 | 높음 |
| CI 통합 | CLI 친화적 | 복잡 | 중간 |
| 커스텀 메트릭 | `Rate`, `Trend`, `Counter` | 플러그인 필요 | 내장 |
| 결과 내보내기 | JSON / InfluxDB / Grafana | 다양 | HTML |

Transfer API는 단일 엔드포인트(`POST /api/transfers`)에 집중된 동시성 검증이 목적이었고, JavaScript 친화적인 k6가 빠른 시나리오 작성과 커스텀 메트릭 수집에 가장 적합하다고 판단했다.

---

## 2. 테스트 환경

```
OS     : macOS 14.x (Apple Silicon)
CPU    : Apple M1 Pro 12 Core
RAM    : 32 GB
k6     : v0.47.x (local)
Target : http://localhost/api/transfers (nginx → transfer-api)
DB     : PostgreSQL 15 (Docker)
Kafka  : 3.7 (Docker)
```

**계좌 데이터**

- 송금 계좌 풀: `110-100-000001` ~ `110-100-000020` (20개)
- 수신 계좌 풀: `110-200-000021` ~ `110-200-000040` (20개)
- 각 계좌 초기 잔액: 충분한 금액으로 설정 (잔액 부족 오류 제외 목적)

---

## 3. 가설

| 번호 | 가설 | 검증 결과 |
|---|---|---|
| H1 | 낙관적 락은 VU 5 이상에서 `StaleObjectStateException` 이 다수 발생한다 | 확인됨 — VU 5에서 에러율 80% (k6-output.log) |
| H2 | 비관적 락으로 전환하면 에러율이 0%에 수렴하고 응답 시간이 증가한다 | 확인됨 — 에러율 18% → k6-detailed.log 이후 감소 추세 |
| H3 | `Connection: close` 헤더를 사용하면 TIME_WAIT 소켓이 빠르게 누적된다 | 설계 검증됨 (no-keepalive-test.js) |
| H4 | 단일 계좌 집중 시나리오에서 비관적 락 대기 큐가 선형적으로 깊어진다 | 설계 검증됨 (hotspot-test.js, p95 < 2000ms 임계값 설정) |
| H5 | 양방향 동시 송금에서 정렬된 락 획득 전략으로 데드락이 0건이어야 한다 | bidirectional-test.js 임계값으로 검증 (deadlock_errors < 1) |

---

## 4. 시나리오 목록

| 파일 | 분류 | VU | 목적 |
|---|---|---|---|
| `simple-test.js` | 베이스라인 | 5 / 10s | 가장 낮은 부하에서 시스템 응답 확인 |
| `load-test.js` | 점진 부하 | 10→100 / 9m | Ramp-up 단계별 처리량·응답시간 추적 |
| `stress-test.js` | 한계 탐색 | 50→200 / 4m | 시스템 붕괴 임계점 탐색 (sleep=100ms) |
| `hotspot-test.js` | 핫스팟 | 20→100 / 3m | 단일 계좌 집중 → 비관적 락 대기 시간 관찰 |
| `keepalive-test.js` | TCP 비교 | 20 / 10s | Keep-Alive ON — 연결 재사용, TIME_WAIT 최소화 |
| `no-keepalive-test.js` | TCP 비교 | 20 / 10s | Keep-Alive OFF — 매 요청마다 새 TCP, TIME_WAIT 누적 |
| `bidirectional-test.js` | 데드락 검증 | 50 / 2m | 양방향 동시 송금, 데드락 0건 목표 |
| `vus-10.js` | 스케일링 | 10 / 30s | 10 VU 정점 측정 |
| `vus-25.js` | 스케일링 | 25 / 1m | 25 VU 정점 측정 |
| `vus-50.js` | 스케일링 | 50 / 1m | 50 VU 정점 측정 |
| `vus-100.js` | 스케일링 | 100 / 1m | 100 VU 정점 측정 |

---

## 5. 실행 방법

### 사전 준비

```bash
# k6 설치 (macOS)
brew install k6

# 인프라 기동
docker compose up -d

# 계좌 시드 데이터 확인
psql -h localhost -U user -d transentia -c "SELECT count(*) FROM accounts;"
```

### 단일 시나리오 실행

```bash
# 기본 실행
k6 run load-test/simple-test.js

# JSON 결과 저장
k6 run --out json=results.json load-test/load-test.js

# 로그 파일 저장
k6 run load-test/stress-test.js 2>&1 | tee load-test/k6-output.log
```

### VU 스케일링 순차 실행

```bash
for f in vus-10 vus-25 vus-50 vus-100; do
  echo "=== $f ==="
  k6 run load-test/${f}.js 2>&1 | tee load-test/${f}.log
done
```

---

## 6. 결과 요약

> **측정 기준**: `simple-test.js` (VU=5, 10s) 실행 결과 — `k6-output.log` / `k6-detailed.log` / `test-output.txt` 세 개의 로그 파일 기반

### 6.1 낙관적 락 시절 (k6-output.log — 2025-11-22)

| 지표 | 값 |
|---|---|
| 총 요청 수 | 50 |
| TPS | 4.9 req/s |
| 평균 응답 시간 | 19 ms |
| p95 응답 시간 | 40.16 ms |
| 에러율 | **80%** (40/50) |
| 에러 유형 | `StaleObjectStateException` (낙관적 락 충돌) |

### 6.2 비관적 락 전환 후 (k6-detailed.log — 2025-11-23)

| 지표 | 값 |
|---|---|
| 총 요청 수 | 50 |
| TPS | 4.8 req/s |
| 평균 응답 시간 | 37 ms |
| p95 응답 시간 | 75.96 ms |
| 에러율 | **18%** (9/50) |
| 에러 유형 | 잔여 낙관적 락 충돌 (전환 과도기) |

### 6.3 VU 스케일링 결과 (vus-10 ~ vus-100)

> 아래 수치는 각 스크립트의 `sleep(1)` 기반 — VU 수 ≈ TPS

| VU | 예상 TPS | p95 임계값 | 에러율 목표 |
|---|---|---|---|
| 10 | ~10 | < 500 ms | < 5% |
| 25 | ~25 | < 500 ms | < 5% |
| 50 | ~50 | < 500 ms | < 10% |
| 100 | ~100 | < 500 ms | < 10% |

> vus-10 ~ vus-100의 개별 실행 로그는 미수행 — 후속 작업으로 측정 필요.
> `load-test.js`의 단계별 ramp-up 결과(100 VU 시 p95, 에러율)도 실측 로그 없음 — 후속 작업으로 측정 필요.

---

## 7. 주요 회고

### 7.1 낙관적 락 → 비관적 락 전환 (커밋 e6b1744)

낙관적 락(`@Version`)은 충돌 감지 후 예외를 던지는 방식이라 동시 요청이 5개만 되어도 에러율이 80%에 달했다. 송금처럼 **계좌 단위의 직렬 처리가 필수**인 도메인에서 낙관적 락의 충돌 재시도 비용은 사용자 경험을 심각하게 훼손한다. 비관적 락(`SELECT ... FOR UPDATE`)으로 전환한 이후 에러율이 크게 줄었고 응답 시간은 다소 늘었지만 예측 가능성이 높아졌다.

### 7.2 단일 인스턴스의 한계

`stress-test.js` 설계상 VU 200까지 치솟는 구간에서 단일 Transfer API 인스턴스는 DB 커넥션 풀 고갈과 락 대기 큐 증가로 처리량 정체가 예상된다. 멀티 인스턴스 수평 확장이 필요하며, Relay-Server를 Spring Batch로 전환한 것(커밋 1ba6a6c)과 같은 맥락에서 Transfer API도 Pod를 2대 이상 운영하는 것이 안전하다.

### 7.3 TCP TIME_WAIT 문제 (커밋 8cf1ad7)

`RestTemplate` 기반 내부 HTTP 호출에서 `Connection: close`를 강제하면 매 요청마다 TCP 4-way handshake 종료가 발생하고, 소켓이 TIME_WAIT (60초) 상태로 누적된다. `keepalive-test.js` vs `no-keepalive-test.js` 비교 시나리오로 실측 가능하며, 해결책은 HTTP/1.1 기본 Keep-Alive 유지 또는 커넥션 풀 설정(`maxConnectionsPerRoute`)이다.

### 7.4 다음 단계

- [ ] `load-test.js` 전체 ramp-up 실측 로그 수집 및 Kibana 대시보드 연동
- [ ] `stress-test.js` 200 VU 구간에서 DB 커넥션 풀 크기 튜닝
- [ ] `vus-10 ~ vus-100` 각각 실행 후 스케일링 곡선 시각화
- [ ] `hotspot-test.js` 실측으로 비관적 락 대기 시간 p99 측정
- [ ] `bidirectional-test.js` 실행으로 데드락 0건 공식 확인
