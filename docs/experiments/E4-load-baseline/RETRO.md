# E4 — 부하 베이스라인 회고

## 1. 가장 큰 문제: 실험을 절반만 했다

이걸 먼저 적어 둔다.

`load-test/` 디렉토리에는 `vus-10.js` ~ `vus-100.js` 가 분명히 있다.
스크립트는 돌릴 준비가 끝나 있었다. 그런데 실제로 돌린 흔적은 simple-test 3회뿐이다.

왜 그랬는가. 솔직하게 적으면 이렇다.

- simple-test 에서 에러율 80% 가 나온 순간 시선이 그쪽으로 쏠렸다.
- 낙관락 → 비관락 전환이 더 급한 일로 보여서, **곡선 측정은 "나중에"** 가 됐다.
- 비관락 전환 직후 simple-test 에서 18% 가 나왔는데, 그 18% 의 원인부터 파야 한다는 생각에 또 곡선이 밀렸다.

결과적으로 베이스라인이라 부를 만한 곡선 데이터는 한 점밖에 없다.
이게 가장 큰 회고다. **"기준선을 깐 다음에 문제를 보자" 라는 순서를 지키지 못했다.**

다음 측정에서는 이렇게 한다.
- 락 전략을 확정한 시점에 곧바로 vus-10 → 25 → 50 → 100 을 일괄 실행.
- 그 결과로 곡선을 먼저 그린 다음, 에러 원인 분석에 들어간다.

---

## 2. 단발 측정의 한계

simple-test 도 회당 10초짜리다.
이 짧은 측정이 왜곡할 수 있는 요소를 정리해 둔다.

### 2.1 warm-up 누락

JVM 은 코드를 처음 만나면 인터프리트로 실행하다가 C1 → C2 컴파일로 단계적으로 최적화한다.
10초짜리 측정은 **이 컴파일이 끝나기 전에 측정이 끝날 수 있다**.

simple-test 의 처음 1~2 초와 마지막 1~2 초의 응답 시간이 같았는지 확인해야 한다.
지금 로그에서는 1초 간격 진행률만 찍혀 있어 확인이 어렵다.

다음 측정에서는:
- warm-up 30s + 본 측정 1m 으로 분리.
- 또는 `--summary-trend-stats="min,p(50),p(90),p(95),p(99),max"` 로 분포를 더 자세히.

### 2.2 같은 머신의 k6 + transfer-api + DB

이건 README.md §2 에 명시돼 있지만, 그 영향을 다시 강조한다.

- k6 가 VU 100 을 돌리면 그 자체로 100 개 이상의 goroutine + 소켓을 쓴다.
- 같은 M1 Pro 12 코어 위에서 transfer-api JVM 도 돈다.
- PostgreSQL, Kafka 도 Docker 컨테이너로 같은 머신.

**측정 대상인 transfer-api 가 받는 자원 = 전체 - k6 가 쓴 만큼 - DB/Kafka 가 쓴 만큼**.
VU 가 늘수록 k6 쪽이 더 많이 가져간다.
따라서 VU 100 에서 TPS 가 꺾여도 그게 transfer-api 의 한계인지 머신의 한계인지 구분 불가.

다음 측정에서는:
- k6 를 별도 머신(또는 별도 Docker 호스트)에서 실행.
- 또는 최소한 k6 와 transfer-api 의 CPU 사용량을 동시에 기록.

### 2.3 Docker 오버헤드

PostgreSQL, Kafka 가 Docker 위에서 돈다.
macOS 의 Docker Desktop 은 사실상 VM 위에서 도는 구조라 네트워크 호출 1회마다 추가 비용이 붙는다.

이걸 빼고 봐야 하는데, 빼본 적이 없다.

---

## 3. 측정하지 않은 것 — 큰 것부터

이 항목을 비워두는 한 RESULTS 의 모든 "추정" 은 추정으로만 남는다.

### 3.1 JVM GC 통계

`-Xlog:gc*` 옵션을 안 켰다.
G1GC 의 Mixed GC 가 부하 중에 얼마나 자주 발생하는지, pause time 이 얼마인지 안 보인다.

- VU 100 시점에 잠깐 응답 시간이 튀면 그게 GC 인지 락 대기인지 구분 불가.
- p99 가 갑자기 커지는 패턴은 GC 가 흔한 원인이다.

다음: `-Xlog:gc*:file=/var/log/gc.log:time,uptime,level,tags:filecount=10,filesize=10m` 추가.

### 3.2 HikariCP / Tomcat 메트릭

Spring Actuator + Prometheus 는 이미 application.yml line 110~129 에 켜져 있다.
그런데 측정 중에 스크랩한 기록이 없다.

봐야 할 것:
- `hikaricp_connections_active` — 풀에서 실제로 쓰는 커넥션 수.
- `hikaricp_connections_pending` — 풀을 기다리는 요청 수.
- `tomcat_threads_busy` — 작업 중인 스레드.
- `tomcat_threads_current` — 현재 스레드 수.

이 네 개만 시계열로 찍어도 "VU 100 에서 무엇이 먼저 포화되는가" 가 답이 나온다.

### 3.3 DB query plan 변화

부하 중에 PostgreSQL 의 plan cache 가 어떻게 동작하는지 안 봤다.
같은 쿼리를 다른 파라미터로 자주 부르면 generic plan 이 잡힐 수 있는데,
이게 비관락 SELECT FOR UPDATE 의 성능과 어떻게 상호작용하는지 모른다.

`pg_stat_statements` 와 `auto_explain` 으로 부하 중 plan 을 볼 수 있다.

### 3.4 Kafka producer / consumer lag

송금 처리 시 transfer-events 토픽으로 이벤트가 발행된다 (application.yml line 99-102).
부하가 producer 쪽에서 막혀도 응답 시간에 영향이 갈 수 있다.

- `kafka_producer_record_send_rate` — 발행 속도.
- `kafka_producer_buffer_pool_wait_ratio` — 버퍼 풀 대기 비율.

이게 0 이 아니면 응답 시간 늘어남의 원인 중 하나가 producer 측이다.

### 3.5 traceId 별 5xx 분류

가장 아쉬운 것 하나.

application.yml line 126-129 에서 tracing 샘플링이 100% 다.
즉 모든 5xx 응답에 traceId 가 붙어 있고, Zipkin/Tempo 에서 stacktrace 까지 추적 가능했다.
그런데 18% 에러의 stacktrace 분포를 직접 뽑은 적이 없다.

이걸 했으면 RESULTS §3.3 의 "낙관락 잔재 vs snowflake 충돌" 비중을 답할 수 있었다.

---

## 4. 후속 실험 분리

E4 의 한 점을 갖고 답할 수 없는 질문들을 별도 실험으로 떼어 낸다.

### E4-1 — 멀티 인스턴스 부하 비교

질문: transfer-api 인스턴스를 2대, 4대로 늘리면 TPS 가 비례해서 늘어나는가.

가설: lock-bound 가 지배적이라면 인스턴스 추가의 효과는 작다.
CPU-bound 라면 거의 선형으로 늘어난다.

설계:
- transfer-api 인스턴스 1 / 2 / 4 대 시나리오.
- 동일한 vus-100.js 시나리오.
- nginx 로드밸런싱.
- DB 는 동일 (공유 자원).

기대 결과:
- 1대 → 2대: TPS 1.3~1.6배 (락 경합으로 선형 미만).
- 2대 → 4대: TPS 1.1~1.3배 (수확체감).
- 만약 거의 선형이면 우리가 락 경합이라 믿었던 게 사실은 CPU bound 였다는 뜻.

### E4-2 — 클라이언트/서버 격리

질문: k6 와 transfer-api 를 다른 머신에 두면 측정 수치가 어떻게 변하는가.

이것 없이는 RETRO §2.2 의 문제가 영구 미해결.

설계:
- k6 컨테이너를 별도 머신에서 실행.
- 또는 macOS 호스트에서 transfer-api 만 Docker 내부, k6 는 호스트.
- 네트워크 RTT 측정도 같이 (`ping`, `iperf`).

비교 지점:
- 동일 시나리오의 simple-test 결과가 같은 머신 케이스와 얼마나 다른가.
- 차이가 10% 이내면 RETRO §2.2 의 우려는 과장. 30% 이상이면 모든 결과 재해석 필요.

### E4-3 — 장시간 안정성 (soak test)

질문: 1시간 이상 부하를 주면 메모리 leak / connection leak / 의외의 cascade failure 가 나오는가.

설계:
- vus-25 또는 vus-50 시나리오를 1시간.
- JVM heap 사용량 / GC 빈도 / Hikari leak detection 동시 기록.
- application.yml line 60 의 `leak-detection-threshold: 60000` (60초) 가 트리거되는지.

기대:
- heap 이 정상 패턴이라면 톱니파처럼 오르내림.
- 단조 증가하면 leak.
- Hikari leak 로그가 뜨면 어딘가에서 커넥션을 안 닫고 있다.

### E4-4 — Spike test

질문: 부하가 천천히 늘 때(ramp-up)와 갑자기 들이부을 때(spike) 시스템의 반응이 다른가.

설계:
- ramp-up: 30초간 0 → 100 VU.
- spike: 0 VU 에서 5초 안에 100 VU.
- 두 케이스에서 첫 1분간 에러율 / p95 비교.

기대:
- ramp-up 에서 견디면 그 부하 자체는 처리 가능.
- spike 에서 무너지면 그건 워밍업 부족 / 풀 사이즈 / circuit breaker tuning 문제.
- 실제 운영의 트래픽 급증 (광고 노출, 푸시 발송 직후) 에 대응하는 능력 측정.

---

## 5. 이번 실험이 남긴 것 (작더라도)

부정적으로만 쓰면 안 되니까 마지막에 정리한다.

남은 사실 두 개가 있다.

- 비관락 전환은 에러율을 5분의 1 이하로 줄였다 (80% → 18%).
- 그런데도 18% 가 남았다는 것은 락만으로 해결되지 않는 다른 5xx 원인이 있다는 신호다.
  최소 두 가지 후보 (낙관락 잔재 / snowflake 충돌) 까지는 좁혔다.

곡선은 못 그렸지만, **다음에 무엇을 측정해야 하는지** 는 또렷해졌다.
이게 한 번의 실험이 다음 실험으로 넘기는 가장 정직한 산출물이라고 생각한다.

---

## 6. 회고를 회고하면

E4 의 결과가 부분적이었던 진짜 이유 하나를 적어 둔다.

문제 발견 → 곧바로 수정 → 부분적인 확인 → 다음 문제 발견.
이 흐름은 일견 자연스러워 보인다. 하지만 **베이스라인을 깔기 전에는 어떤 수정도 "개선" 인지 모른다**.

비관락 전환이 "개선" 이라고 자신 있게 말하려면
**같은 시나리오의 곡선이 위에서 아래로 내려갔다** 를 보여야 한다.
한 점에서 한 점으로 옮긴 것 (에러율 80% → 18%) 만으로는 다른 VU 구간에서 어떻게 변했는지 모른다.

다음 실험의 첫 단계는 **베이스라인 곡선을 먼저 깐다** 다.
그 다음에야 개입의 효과를 측정한다.
