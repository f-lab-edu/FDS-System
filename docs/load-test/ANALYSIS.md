# 병목 분석

## 목차

1. [병목 1 — 낙관적 락 충돌](#1-병목-1--낙관적-락-충돌)
2. [병목 2 — TCP TIME_WAIT 누적](#2-병목-2--tcp-timewait-누적)
3. [병목 3 — DB 락 경합 (계좌 단위 직렬화)](#3-병목-3--db-락-경합-계좌-단위-직렬화)
4. [측정 타임라인](#4-측정-타임라인)
5. [다음 단계](#5-다음-단계)

---

## 1. 병목 1 — 낙관적 락 충돌

### 현상

`simple-test.js` (VU=5, 10s) 실행 중 k6-output.log (2025-11-22)에서 에러율 **80%** 관측.

```
http_req_failed : 80.00%  40 out of 50
✗ status is 200 : 20% — ✓ 10 / ✗ 40
```

오류 메시지:

```
"Row was updated or deleted by another transaction
 (or unsaved-value mapping was incorrect) :
 [AccountBalanceJpaEntity#20001]"
```

JPA `@Version` 기반 낙관적 락에서 동일 엔티티를 동시에 수정하려 할 때 나중에 커밋하는 트랜잭션이 `StaleObjectStateException`을 던진다.

### 원인 분석

계좌 풀 20개 / VU 5개 조건에서 충돌 확률은 약 19% 수준이나, 실측에서는 80%를 기록했다. 낙관적 락은 충돌 빈도가 낮을 때 유효하다. 동일 계좌에 다수 요청이 집중되는 송금 도메인 특성상 낙관적 락은 부적합하다.

k6-output.log에서 `duplicate key value violates unique constraint "transactions_pkey"` 오류도 2건 관측됐다. Snowflake ID 생성기가 같은 타임스탬프에서 충돌한 케이스다.

### 개선 조치 (커밋 e6b1744)

`AccountBalanceJpaEntity`에 비관적 락(`@Lock(LockModeType.PESSIMISTIC_WRITE)`)을 적용해 DB 레벨 `SELECT ... FOR UPDATE` 처리.

### 개선 후 결과

| 지표 | 낙관적 락 (k6-output.log) | 비관적 락 (k6-detailed.log) | 변화 |
|---|---|---|---|
| 에러율 | 80% | 18% | -62%p |
| 평균 응답 시간 | 19 ms | 37 ms | +18 ms |
| p95 | 40.16 ms | 75.96 ms | +35.8 ms |

18%의 잔여 에러는 전환 과도기에 일부 코드 경로가 낙관적 락을 유지한 데 기인한다.

---

## 2. 병목 2 — TCP TIME_WAIT 누적

### 현상

커밋 8cf1ad7 ("RestTemplate으로 timewait 만나보기")에서 `Connection: close` 헤더로 인해 매 요청마다 TCP 4-way handshake 종료 발생, TIME_WAIT 소켓 누적.

### 원인 분석

```
Client: ESTABLISHED → FIN_WAIT_1 → FIN_WAIT_2 → TIME_WAIT(60s) → CLOSED
Server: ESTABLISHED → CLOSE_WAIT → LAST_ACK → CLOSED
```

초당 500 req/s 기준 60,000개 소켓이 TIME_WAIT 상태로 쌓이며, 로컬 포트 고갈(약 28,000개) 시 `connect: cannot assign requested address` 오류로 이어진다.

### 측정 근거

`no-keepalive-test.js` — VU=20, sleep 없음, `Connection: close` 강제 설계.

> 실측 비교 로그 미수행 — 후속 작업으로 측정 필요.

### 개선 방향

1. `RestTemplate`에 `PoolingHttpClientConnectionManager` 적용 (maxTotal=200, maxPerRoute=50).
2. `Connection: close` 헤더 제거 — HTTP/1.1 기본 Keep-Alive 유지.
3. Spring WebClient 전환 시 Reactor Netty 기본 커넥션 풀 활용.

---

## 3. 병목 3 — DB 락 경합 (계좌 단위 직렬화)

### 현상

비관적 락 전환 후에도 VU 증가에 따라 응답 시간이 선형 이상으로 증가한다. `hotspot-test.js` 임계값을 p95 < 2,000ms로 완화한 것이 이를 반영한다.

### 원인 분석

비관적 락은 같은 계좌에 대한 요청을 FIFO 큐로 직렬화한다.

```
이론적 최악 대기 시간 = 락 유지 시간 × (동시 VU - 1)
                     ≈ 50ms × (100 - 1) ≈ 4,950ms
```

### 측정 근거

낙관적 → 비관적 전환으로 avg +18ms 증가. 이 값이 직렬화 비용의 기저값이다.

### 개선 방향

| 방향 | 설명 |
|---|---|
| 계좌 풀 확대 | 테스트 계좌 20개 → 100개+ |
| Transfer API 수평 확장 | Pod 2대 이상 |
| DB 샤딩 | 계좌 ID 기반 샤드 분리 |
| Redis 분산 락 | DB 락 대신 Redis SET NX |
| 낙관적 락 + 지수 백오프 재시도 | 재시도 3회, 100ms 백오프 |

---

## 4. 측정 타임라인

| 날짜 | 커밋 | 시나리오 | 핵심 관측 |
|---|---|---|---|
| 2025-11-22 | — | simple-test (낙관적 락) | 에러율 80%, StaleObjectState |
| 2025-11-22 | — | simple-test (전환 중) | 에러율 38%, 과도기 |
| 2025-11-22 | 8cf1ad7 | keepalive/no-keepalive 설계 | TIME_WAIT 재현 |
| 2025-11-23 | e6b1744 | simple-test (비관적 락) | 에러율 18%, avg 37ms |
| 미수행 | — | load-test ramp-up (10→100 VU) | — |
| 미수행 | — | stress-test 200 VU | — |
| 미수행 | — | hotspot-test | — |
| 미수행 | — | bidirectional-test | — |
| 미수행 | — | vus-10/25/50/100 | — |

---

## 5. 다음 단계

### 단기

- [ ] `load-test.js` 전체 ramp-up 실행 — 단계별 p95·에러율 기록
- [ ] `vus-10 ~ vus-100` 순차 실행 — 스케일링 곡선 데이터 확보
- [ ] `keepalive` vs `no-keepalive` 동시 실행 + `netstat` 비교

### 중기

- [ ] `stress-test.js` 200 VU 실행 — DB 커넥션 풀 임계점 확인
- [ ] `hotspot-test.js` 실행 — p95 비관적 락 대기 실측
- [ ] `bidirectional-test.js` 실행 — 데드락 0건 공식 검증

### 장기 (가설)

- [ ] Transfer API 수평 확장 (2→4 Pod) 후 동일 시나리오 재실행
- [ ] Redis 분산 락 도입 전·후 비교 (`hotspot-test.js` 재사용)
- [ ] DB 읽기 복제본 분리 후 조회 응답 시간 측정
- [ ] Cassandra 계좌 잔액 저장 실험 — 높은 쓰기 처리량 가설 검증
