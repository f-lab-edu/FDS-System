# Phase 1 회고 — Outbox 패턴과 Relay 파티셔닝

> 작성 시점에 이 시스템은 송금 API 한 대, Relay 3대, Kafka 한 클러스터, Postgres 한 대로 구성되어 있었다.
> 이 회고는 "왜 그 길로 갔는가, 그래서 어디서 깨졌는가"를 남기는 데 목적이 있다. 자랑이 아니라 학습 기록이다.

---

## 1. 무엇을 만들었나

Phase 1의 결과물은 세 가지로 요약된다.

1. **Outbox 테이블 `transfer_events`** — 송금 트랜잭션과 동일한 DB 트랜잭션에서 이벤트 행을 저장한다.
2. **Relay 인스턴스 3대** — `transfer-relay-0/1/2`. 각 인스턴스가 `MOD(event_id, 3) = N` 조건으로 자기 파티션만 읽는다.
3. **Spring Batch 이관된 Reader/Writer 파이프라인** — `services/transfer/instances/transfer-relay/src/main/kotlin/.../batch/TransferOutboxItemReader.kt` 등에 위치한다.

처음부터 이렇게 생기지 않았다. 단일 인스턴스 → 단일 인스턴스 멀티스레드 → 멀티 인스턴스 + 파티셔닝 → Spring Batch 이관 순서로 다섯 번 정도 모양이 바뀌었다. 각 단계마다 무엇이 깨졌는지가 이 회고의 핵심이다.

---

## 2. 처음 판단 — 왜 Outbox였나

송금이 완료된 직후 Kafka로 이벤트를 쏴야 했다. 가장 쉬운 형태는 이거였다.

```kotlin
@Transactional
fun transfer(command: TransferCommand) {
    transactionRepository.save(transaction)
}

// 트랜잭션 종료 후
@TransactionalEventListener(phase = AFTER_COMMIT)
fun publish(event: TransferEvent) {
    kafkaTemplate.send("transfers", event)
}
```

처음에는 이걸로 충분하다고 생각했다. 트랜잭션이 커밋된 다음에 보내니까 "DB 저장은 됐는데 이벤트가 빠지는" 일은 없다고 봤기 때문이다.

그런데 한 번 더 생각해 보니, 트랜잭션이 커밋된 직후에 JVM이 죽거나, Kafka 브로커가 일시적으로 끊기면 그 이벤트는 영원히 사라진다. 트랜잭션 커밋과 Kafka send는 같은 단위가 아니다. AFTER_COMMIT은 "트랜잭션이 끝났다는 신호"일 뿐, "다음에 일어날 일을 보장"하지 않는다.

여기서 선택지가 세 개 있었다.

| 선택지 | 장점 | 단점 |
|---|---|---|
| (A) 2PC / XA 분산 트랜잭션 | 강한 일관성 | 운영 복잡도, 성능 페널티, Kafka가 XA 미지원 |
| (B) Debezium CDC | DB 트랜잭션만 신뢰하면 됨, 폴링 없음 | Postgres WAL 권한, Debezium 인프라 도입 부담, 운영 경험 부재 |
| (C) Outbox 테이블 + Relay 폴링 | 단일 트랜잭션, 인프라 추가 없음 | Polling 비용, 중복 발행 가능성 |

(A)는 처음부터 후보가 아니었다. Kafka가 XA를 정상 지원하지 않는다는 사실 하나만으로도 끝이다. (B)는 매력적이었지만, 당시 Debezium을 켰다가 끄는 비용보다 Outbox 한 장 추가하는 비용이 훨씬 적었다. 무엇보다 Debezium은 "외부 컴포넌트 한 개 더"라는 운영 부담을 가져온다. CDC를 도입하지 않은 게 아니라, 도입을 뒤로 미뤘다고 보는 게 정확하다.

그래서 (C). Outbox 행과 송금 행이 같은 `@Transactional` 안에서 저장된다. DB 트랜잭션이 끝났다면 이벤트도 반드시 적혀 있다.

```kotlin
@Transactional
fun transfer(command: TransferCommand) {
    transactionRepository.save(transaction)
    outboxRepository.save(event)  // 같은 트랜잭션
}
```

이게 첫 결정이었다.

---

## 3. 무엇이 잘못됐나 — 멀티 인스턴스의 환상

Relay를 한 대로 굴리다 보니 처리량이 평탄했다. "그러면 3대로 늘리면 3배 되겠지"라고 생각했다. 동시에 락 경합을 피하려고 `FOR UPDATE SKIP LOCKED`를 썼다. 표면적으로는 정합성이 깨지지 않는다.

그런데 부하 테스트를 돌려보니 숫자가 이상했다.

```
Before (파티셔닝 없음, SKIP LOCKED만 사용):
  Instance 0: 3,000건 (30.0%) — DB 쿼리 12회
  Instance 1: 3,000건 (30.0%) — DB 쿼리 3회
  Instance 2: 2,000건 (20.0%) — DB 쿼리 1,658회
  미처리: 20%
  총 쿼리: 1,673회
```

세 가지가 동시에 문제였다.

**1) 분배가 안 균등하다.** Instance 2는 2,000건만 처리했고, 나머지 1,000건은 어디 갔는가? 측정 종료 시점까지 다른 인스턴스가 먼저 잡아갔거나, Instance 2가 들고 있다가 락 풀린 사이에 처리 못한 것들이다. 운영 입장에서 한 인스턴스만 노는 상황이 예측 불가능하다는 게 더 큰 문제였다.

**2) Instance 2가 빈 배치를 1,658회 조회했다.** SKIP LOCKED는 락 잡힌 행을 건너뛰지만, 그 사이에 다른 인스턴스가 이미 다 처리해버렸다면 빈 결과를 받는다. 그런데 그 빈 조회도 DB 입장에서는 인덱스 스캔이다. 평상시에는 무시할 만하지만, 1,673회 중 1,658회가 빈 조회라는 것은 곧 "DB 부하의 99%가 무의미한 스캔"이라는 뜻이다.

**3) 빠른 인스턴스가 독식한다.** 어떤 인스턴스가 빠르게 락을 잡느냐는 GC pause, network jitter, OS scheduling에 따라 매번 달라진다. 결국 분배는 운에 좌우된다.

여기서 처음 가설이 깨졌다. "SKIP LOCKED는 락 경합을 피한다"는 맞지만, 그게 "균등 분배를 보장한다"는 아니다. 두 개를 한 묶음으로 생각했던 게 실수였다.

---

## 4. 어떻게 개선했나 — 파티셔닝

해결책은 단순했다. 각 인스턴스에 고유 ID를 부여하고, `MOD(event_id, N) = instanceId` 조건을 쿼리에 추가했다.

```sql
-- Instance 0
SELECT * FROM transfer_events
WHERE status = 'PENDING' AND MOD(event_id, 3) = 0
ORDER BY event_id LIMIT 1000
FOR UPDATE SKIP LOCKED
```

이제 인스턴스 사이에 같은 행을 두고 경합할 일이 없다. 락이 아니라 쿼리 차원에서 행이 갈린다.

결과:

```
After (MOD 파티셔닝):
  Instance 0: 3,333건 (33.3%) — DB 쿼리 7회
  Instance 1: 3,334건 (33.3%) — DB 쿼리 3회
  Instance 2: 3,333건 (33.3%) — DB 쿼리 7회
  총 쿼리: 17회 (Before 대비 99% 감소)
```

전체 처리 시간은 18,360ms → 20,690ms로 미세하게 늘었다. MOD 연산이 인덱스를 깨끗하게 활용하지 못하기 때문이다. 그렇지만 이건 의도된 트레이드오프였다. 단일 머신 벤치에서 2초 늘어나는 대신, 프로덕션에서 다음을 얻는다.

- DB 쿼리 수 99% 감소 → DB에 가해지는 무의미한 부하 제거
- 분배가 100% 예측 가능 → 운영자가 "Instance 2 처리량이 떨어지면 그 인스턴스 문제"라고 단언할 수 있음
- 락 경합이 없음 → 동시성 시나리오에서 일관된 성능

성능 테스트 문서에서 "처리 시간 단축이 아니라 예측 가능성에 가치를 둔다"고 적은 이유가 여기다. 처리 시간만 따지면 SKIP LOCKED가 미세하게 빠르다. 그러나 운영자는 "오늘은 30/30/20, 내일은 25/40/15"를 받아들이지 못한다.

처리량 자체도 470 TPS → 1,410 TPS 정도로 올랐다. 단일 인스턴스 대비 정확히 선형 확장이 이루어졌다. 이게 파티셔닝의 진짜 가치다.

---

## 5. 두 번째 깨짐 — 부하 테스트 중 만난 락 실패

k6로 100 VU 시나리오를 돌리면서 또 다른 문제를 만났다. `services/.../load-test/vus-100.js` 시나리오에서 송금 실패율이 비정상적으로 올라갔다. 로그를 보니 낙관적 락 충돌이 다수였다.

처음 설계할 때 계좌 잔액 조회/차감에 `@Version` 기반 낙관적 락을 썼다. 이유는 흔한 그것이다. "DB 락 안 잡으니까 동시성이 좋겠지". 그런데 한 계좌에 동시에 송금이 몰리는 시나리오(예: 같은 송신자 계좌에서 여러 출금이 몰리는 경우, hotspot-test.js)에서 충돌율이 급증했다.

낙관적 락의 전제는 "충돌이 드물다"이다. 그런데 부하 테스트의 hotspot 시나리오는 정확히 그 전제를 깨는 패턴이다. 결국 비관적 락(`SELECT ... FOR UPDATE`)으로 회귀했다.

이게 흥미로운 지점이다. 보통 비관적 락에서 낙관적 락으로 옮겨가는 게 "발전"이라고 생각한다. 그런데 이 도메인에서는 그 반대였다. 부하 패턴이 충돌을 유도하는 구조였기 때문이다. 즉, **"낙관적 락이 더 좋다"는 명제는 도메인 패턴에 종속된다**. 그걸 부하 테스트로 확인했다.

이 결정은 거꾸로 가는 것처럼 보이지만, 측정값이 그렇게 가리켰다. e6b1744 커밋 메시지에 그대로 남아 있다.

---

## 6. 세 번째 모양 — Spring Batch로의 이관

처음 Relay는 `@Scheduled`로 단순히 폴링하는 구조였다. 그러다 단일 인스턴스 멀티스레드 전략으로 한 번 바꿨다(82eedde, 9bf9af6 커밋). 한 인스턴스 안에 ThreadPool을 두고 여러 스레드가 각자 파티션을 잡는 형태였다. 운영 인스턴스 수를 줄이려는 시도였다.

그런데 운영 관점에서 보니 이게 만족스럽지 않았다. 이유는 세 가지였다.

1. **재시도 로직, skip 정책, listener를 직접 구현해야 한다.** 이미 Spring Batch가 잘 만든 걸 굳이 다시 만들고 있다.
2. **JobLauncher / JobRepository로 실행 이력이 자연스럽게 남는다.** 자체 구현으로는 "이번 배치에서 몇 건 처리했고 몇 건 실패했나"를 따로 로깅해야 한다.
3. **`FaultTolerantStepConfigurer`로 retry/skip 정책을 선언적으로 분리할 수 있다.** Reader/Processor/Writer가 깔끔하게 분리되니 단위 테스트도 쉬워진다.

그래서 1ba6a6c 커밋에서 Spring Batch로 이관했다. 멀티 인스턴스 + 파티셔닝 + Spring Batch가 현재 모양이다.

여기서 한 번 더 생각이 바뀐 지점이 있다. 이관 전에는 "Spring Batch는 야간 정산용 무거운 도구"라고 생각했다. 그런데 막상 써 보니 1초 단위 폴링 배치에도 충분히 맞는다. 무겁다고 느꼈던 건 JobRepository에 메타 테이블 9개가 생성되는 것 정도이고, 그건 운영 비용에 비해 얻는 게 훨씬 컸다.

---

## 7. 메트릭 요약

| 지표 | Before(파티셔닝 없음) | After(파티셔닝) | 개선 |
|---|---|---|---|
| 처리량 (TPS) | 470 | 1,410 | 3배 (선형 확장) |
| DB 쿼리 수 | 1,673회 | 17회 | 99% 감소 |
| 분배 균등성 | 30/30/20 | 33.3/33.3/33.3 | 균등 달성 |
| 빈 배치 조회 | 1,658회 | 0회 | 완전 제거 |
| Lock 충돌 (100 VU hotspot) | 실패율 급증 | 비관락 전환으로 안정화 | 도메인 패턴에 맞춤 |

처리 시간 자체는 18.4초 → 20.7초로 약간 늘었지만, 이건 단일 머신에서 잰 값이고 프로덕션 환경에서는 DB 부하 감소가 더 중요한 지표다.

---

## 8. 내가 했던 실수

회고이므로 깔끔하게 정리된 결과만 적지 않는다. 다음은 시간 순서대로의 실수다.

**(1) "AFTER_COMMIT으로도 충분하다"고 처음에 판단했다.** Kafka send가 commit 이후 일어나니까 DB는 항상 최신이라고 본 것이다. 그런데 그 순간 JVM이 죽으면 Kafka 이벤트는 없다. 처음에 이걸 "edge case"라고 부르며 넘겼던 게 후회된다. 금융 도메인에서 edge case는 결국 사고로 돌아온다.

**(2) "SKIP LOCKED면 멀티 인스턴스 자동 동작"이라고 가정했다.** 락 경합과 분배는 별개의 문제다. SKIP LOCKED는 락 경합만 푼다. 분배 균등성은 쿼리 차원에서 풀어야 한다. 두 개를 한 묶음으로 본 게 첫 번째 큰 실수였다.

**(3) Instance 2가 빈 배치 1,658회 조회하는 걸 한동안 인지하지 못했다.** TPS만 보고 있었다. DB 쿼리 수를 같이 봤어야 했다. 메트릭 하나만 들여다보는 게 얼마나 위험한지 배웠다.

**(4) 낙관적 락을 "더 발전된 방식"이라고 무의식적으로 가정했다.** 부하 테스트가 그걸 깰 때까지 의심하지 않았다. "발전된 방식"이라는 단어 자체가 도메인 패턴을 무시한 표현이다.

**(5) Spring Batch를 "무겁다"고 느껴서 자체 멀티스레드 풀로 우회했다.** 결국 다시 돌아왔다. 이미 잘 만들어진 도구를 거부하는 데는 그만한 근거가 있어야 한다. "그냥 무거워 보여서"는 근거가 아니다.

---

## 9. 남은 리스크 / Phase 2로 넘어가는 지점

Phase 1이 끝났을 때 다음이 남아 있었다.

- **Outbox 행의 retention 정책이 없다.** 발행 완료된 행이 계속 쌓이면 테이블이 커진다. 인덱스 효율도 떨어진다. 주기적 archive/delete 정책이 필요하다.
- **파티션 수가 인스턴스 수와 결합되어 있다.** 인스턴스를 4대로 늘리려면 MOD 3 → MOD 4로 쿼리를 바꿔야 한다. 모듈로 연산 기반 파티셔닝의 본질적 한계다. 향후 consistent hashing이나 leader-elected sharding으로 옮겨가야 할 수 있다.
- **분배는 균등하지만 traceId가 끊긴다.** Outbox 행을 Kafka로 발행하는 시점에 송금 API의 traceId가 따라가지 못한다. 이게 Phase 2의 출발점이 된다.
- **모니터링이 부족하다.** 각 Relay 인스턴스의 처리 분포를 실시간으로 확인할 수 없다. Slack 알림도 없다.

Phase 2에서는 traceId 전파와 ELK 구축, 그리고 Redis 캐시 도입이 다음 주제가 된다.

---

## 10. 빅테크 인터뷰 Q&A

이 Phase에서 자주 받을 만한 질문들. 답변은 위에서 정리한 판단의 흐름을 따른다.

### Q1. 왜 Debezium 같은 CDC가 아니라 Outbox + 폴링을 선택했나?

CDC를 거부한 게 아니라 시점을 미뤘다. Debezium은 Postgres WAL 권한, Kafka Connect 클러스터, 스키마 진화 정책까지 따라온다. 이 시점의 팀이 가진 운영 캐파보다 부담이 컸다. 반면 Outbox는 테이블 하나 추가, 인덱스 두 개, Relay 코드 한 묶음이면 끝난다. "더 좋은 방법"이 아니라 "지금 팀이 감당 가능한 방법"을 골랐다. 트래픽이 더 늘어나거나 폴링 비용이 무시 못 할 수준이 되면 Debezium 전환은 자연스러운 다음 스텝이다.

### Q2. 왜 낙관적 락에서 비관적 락으로 회귀했나? 발전이 아니라 퇴행 아닌가?

도메인 부하 패턴이 낙관락의 전제를 깬다. 낙관락은 "충돌이 드물다"는 전제 위에서 성립한다. 그런데 우리 부하 테스트 hotspot 시나리오는 같은 계좌에 여러 출금이 몰리는 패턴이다. 100 VU에서 실패율이 급증했다. 비관락은 행 단위 직렬화를 강제하지만, 우리가 잡는 락은 짧고(밀리초 단위) 인덱스 기반이라 wait queue가 길어지지 않는다. "낙관락이 더 좋다"는 명제는 도메인 패턴에 종속된다는 걸 확인한 결정이다. 무엇이 더 좋은가가 아니라, 이 도메인에서 어느 쪽이 측정값으로 더 안정적인가를 본 것이다.

### Q3. 파티션 수(MOD N)는 어떻게 정했나? 4대, 5대로 늘리면 어떻게 할 건가?

현재 3대는 단순히 "3개의 Relay 인스턴스로 시작했다"는 운영적 결정이지, 트래픽 계산으로 도출한 숫자가 아니다. 그래서 이 구조의 본질적 한계가 있다. 인스턴스 수를 바꿀 때마다 쿼리의 MOD 값을 바꿔야 한다. 무중단으로 늘리려면 새 파티션 매핑으로 점진 이관하는 로직이 필요하다. 향후 확장 시에는 (a) 가상 파티션(N=64) 도입하고 인스턴스 ↔ 파티션을 동적 매핑하는 방식, 또는 (b) consistent hashing 기반 sharding으로 옮겨가는 게 자연스러운 진화 방향이다. 지금은 3대로 충분한 트래픽이라 미리 만들지 않았다.

### Q4. SKIP LOCKED와 MOD 파티셔닝을 같이 쓰는데, SKIP LOCKED가 여전히 필요한가?

파티셔닝만으로는 같은 파티션 안의 동시성을 막지 못한다. 한 Relay 인스턴스가 멀티스레드로 동작하거나, 같은 인스턴스의 두 번째 폴링 사이클이 첫 번째와 겹칠 수 있다. 그래서 같은 파티션의 같은 행을 두 스레드가 잡지 않도록 SKIP LOCKED는 그대로 둔다. 두 메커니즘이 다른 레벨에서 동작한다. MOD는 인스턴스 사이의 분배, SKIP LOCKED는 인스턴스 내부의 직렬화다.

### Q5. Outbox 패턴의 중복 발행 문제는 어떻게 다루나?

Outbox는 "at-least-once" 모델이다. Relay가 발행 직후 status 업데이트 전에 죽으면 같은 이벤트가 두 번 발행된다. 이건 Outbox의 본질이므로 producer 쪽에서 막을 수 없다. 대신 컨슈머 쪽에서 idempotency를 보장해야 한다. 현재 FDS 컨슈머는 `eventId`를 기반으로 멱등 처리하고, PostgreSQL의 unique constraint로 같은 eventId가 두 번 저장되지 않도록 강제한다. 즉, "한 번만 발행"이 아니라 "여러 번 발행돼도 한 번처럼 처리"가 우리 모델이다.

---

## 관련 코드 경로

- `services/transfer/instances/transfer-relay/src/main/kotlin/io/github/hyungkishin/transentia/relay/batch/TransferOutboxItemReader.kt`
- `services/transfer/instances/transfer-relay/src/main/kotlin/io/github/hyungkishin/transentia/relay/batch/TransferOutboxItemWriter.kt`
- `services/transfer/instances/transfer-relay/src/main/kotlin/io/github/hyungkishin/transentia/relay/config/TransferOutboxBatchConfig.kt`
- `services/transfer/instances/transfer-relay/src/main/kotlin/io/github/hyungkishin/transentia/relay/config/FaultTolerantStepConfigurer.kt`
- `common/common-domain/src/main/kotlin/io/github/hyungkishin/transentia/common/outbox/transfer/ClaimedRow.kt`
- 부하 테스트 시나리오: `load-test/vus-10.js`, `load-test/vus-25.js`, `load-test/vus-50.js`, `load-test/vus-100.js`, `load-test/hotspot-test.js`
- 성능 측정 결과: `docs/etc/performance-test.md`
- 파티셔닝 설계: `docs/etc/partitioning-strategy.md`
