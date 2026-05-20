# RUNBOOK — 알림을 받았을 때 어떻게 움직이나

알림이 떴을 때 사람이 따라가는 절차다. 자동화가 닿지 않는 자리, 즉 원인이 둘 이상일 수 있어서 사람이 한 번 보고 골라야 하는 자리만 적었어요. `restart` 한 줄로 끝나는 케이스는 굳이 절차를 늘리지 않았다.

여기 적힌 임시 조치 중 상당수는 **실제 운영에서 검증되지 않았다**. 가설로 적은 자리는 항목 끝에 명시한다.

관련 문서: [FAILURE-MODES](FAILURE-MODES.md), [MULTI-INSTANCE](MULTI-INSTANCE.md), [LIMITATIONS](LIMITATIONS.md), [monitoring/prometheus-alerts.yml](../monitoring/prometheus-alerts.yml).

---

## 0. 공통 첫 5분

알림 종류와 무관하게 가장 먼저 보는 자리. 5분 안에 "지금 사용자가 진짜 피해를 보고 있나" 만 판단하면 된다. 원인은 그다음이에요.

1. **Grafana — Transentia Overview 를 연다.** TPS 가 0 이거나 평소 1/10 이하면 사용자 영향이 진행 중이다. 5xx 비율 패널과 p95 패널을 같이 봐서, 둘 다 튀면 데이터 계층을 의심한다. 패널 구성: TPS, p50/p95/p99, 5xx 비율, Redis CB 상태, Redis Fallback, FDS Decision 분포, FDS analyze p95, Outbox PENDING, Outbox 최고령 PENDING, Kafka lag, JVM Heap, GC pause.

2. **Kibana — `app-logs-*` 에서 traceId 로 한 건 따라간다.** 알림에 instance 가 박혀 있으면 그 인스턴스의 최근 1분 ERROR 부터. 사용자 신고가 같이 들어왔다면 그 신고의 transferId 로 traceId 를 잡고 transfer-api → Kafka → FDS 까지 묶어 본다. `d463d3a` 이후 traceId 가 동일하게 흐르도록 정리됨.

3. **Slack 스레드를 잡는다.** critical 은 `#fds-incident`, warning 은 `#fds-monitoring`, phase 전환은 `#fds-roadmap` 로 라우팅됨. 5분 안에 같은 알림이 두 번 오면 동료가 같이 받고 있을 가능성이 크니 스레드를 빨리 만들어 두지 않으면 두 명이 따로 움직이게 돼요.

4. **최근 30분 안의 배포를 확인한다.** 알림 원인의 절반 이상이 직전 변경이라고 보는 게 안전하다.

여기까지 5분. 이 시점에 "사용자 영향 진행 중인가 / 데이터가 새고 있는가" 둘 다 No 라면 알림별 절차로 천천히 내려가도 된다.

---

## 1. 알림별 대응

### 1.1 TransferApiDown (critical)

`up{service="transfer-api"} == 0` 1분. 단일 인스턴스라면 송금 전부 멈춰요.

의심 원인 — OOM 으로 죽고 재시작 루프(직전에 `JvmHeapHigh` 떴는지 같이 보면 거의 확정), PostgreSQL 이 먼저 죽어서 HikariCP startup 실패(`up{job="postgres"}` 동반), 직전 배포의 설정 오류(컨테이너 로그 첫 30줄에 보통 나옴).

먼저 보는 곳: `docker compose logs --tail=200 transfer-api`, JVM Heap 패널, Kibana 의 같은 instance ERROR.

임시 조치: OOM 이면 컨테이너가 보통 자동 재시작했을 텐데(`restart: unless-stopped`) 루프면 `-Xmx` 1.5배 키운 이미지로 재배포. PostgreSQL 이 원인이면 1.5 로 점프. 직전 배포가 원인이면 망설이지 말고 rollback.

영구 조치: `-XX:+HeapDumpOnOutOfMemoryError` 가 아직 안 들어가 있어요. 이번 incident 직후 PR 로 올리고, dump 가 잡혔다면 회고에 retain 원인을 같이 붙인다.

---

### 1.2 FdsApiDown (high)

`up{service="fds-api"} == 0` 1분. FDS 가 죽어도 송금은 흐른다 — 의도된 설계 ([FAILURE-MODES 3.4](FAILURE-MODES.md)). 사용자 체감 영향은 없지만 **이상거래가 분석 없이 통과 중**이라는 게 더 무겁다.

의심 원인 — Kafka Streams State Store(RocksDB) 손상으로 부팅 시 changelog replay 가 길어져 liveness 실패(1주일치면 수십 분), ES 가 죽어서 AccessLog 적재 실패로 일부 룰 timeout(가설), 그냥 OOM.

먼저 본다: `docker compose logs fds-api | grep -i 'rocksdb\|state'`, FDS analyze p95, Kafka consumer lag.

임시 조치: State Store 손상이 의심되면 컨테이너 죽이고 RocksDB 디렉터리 비운 뒤 재시작 → 전체 replay. lag 가 분 단위로 쌓이는 걸 감수해야 해요. 5분 내 복구 안 되면 incident 채널에 "이상거래 분석 공백 X분 시작" 을 명시. 사후 회계 검증의 시작점이 된다.

영구 조치: standby replica 부재(`num.standby.replicas: 0`)가 근본 원인 ([MULTI-INSTANCE 3.3](MULTI-INSTANCE.md)).

사후: 공백 구간의 transferId 를 뽑아 `fraud_detections` backfill, `OPS_AGENT` 가 수동 리뷰. **이 절차는 실제 운영에서 검증 안 됐어요.**

---

### 1.3 TransferRelayDown (high)

1대 down 과 전부 down 이 영향이 다르다.

**1대 down**: MOD 파티셔닝 가정이 깨져서 `MOD(event_id, 3) = X` 인 이벤트가 처리 안 됨 ([MULTI-INSTANCE 2](MULTI-INSTANCE.md)). 30분이면 그 파티션의 outbox 가 수천 건 누적. 다른 인스턴스가 자동 인수하지 않는다 — 정적 파티셔닝이라.

**전부 down**: outbox 발행 100% 정지. 송금 자체는 정상 commit. FDS 분석만 정지. 복구 직후 burst 발행으로 Kafka 가 잠시 흔들릴 수 있는데 이건 시뮬레이션 안 해봤어요.

임시 조치: 1대만 죽었으면 재시작이 첫 카드. 재시작 실패 시 다른 인스턴스 하나의 `INSTANCE_ID` 를 죽은 쪽으로 임시 변경해서 흡수 — 단 이건 **정적 파티셔닝 위에서 손으로 하는 곡예**라 두 인스턴스가 동시에 같은 파티션을 잡으면 중복 발행 위험. 정말 마지막 카드예요. 전부 죽었으면 PENDING 이 1만 건 넘기 전에 복구 + producer rate 모니터링.

영구 조치: 동적 파티셔닝(Kafka 컨슈머 그룹 위임)으로 교체 — ADR-003 의 대안 항목.

사후: [MULTI-INSTANCE 2.5](MULTI-INSTANCE.md) 의 "시뮬레이션 0회" 항목을 이번 incident 의 실제 회복 시간으로 갱신.

---

### 1.4 RedisCircuitBreakerOpen (high)

`resilience4j_circuitbreaker_state{name="redis", state="open"} == 1` 30초. Redis 가 죽으면 일일 한도 검증이 **fail-open** 으로 동작해서 한도 초과 거래가 그냥 통과한다 ([FAILURE-MODES 1.4](FAILURE-MODES.md)). 이게 가장 무거운 자리.

의심 원인 — Redis 컨테이너 down(단일 노드), 응답 지연(네트워크/GC)으로 CB threshold 초과, 직전 배포에서 키 스키마 변경으로 OOM.

먼저 본다: `docker compose ps redis`, `redis-cli ping`, Redis Fallback 패널.

임시 조치: Redis 자체가 죽었으면 재시작. 10초 후 CB half-open 자동 전이(`wait-duration-in-open-state: 10s`).

**여기서 끝내면 안 된다.** OPEN 이던 시간 동안의 송금을 `transactions` 테이블로 사후 검증한다. user_id 별 합산이 일일 한도를 넘는 거래가 있다면 회계팀과 정정.

응답 지연이 원인이면 Redis `slowlog` — `KEYS *` 같은 잘못된 명령이 어느 인스턴스에서 들어왔는지 추적.

영구 조치: fail-open vs fail-closed 정책은 비즈니스 결정. 한도 우회 사고가 한 번이라도 발생하면 ADR 로 옵션 전환을 남길 시점. Redis Sentinel/Cluster 도입은 P1.

---

### 1.5 RedisFallbackHighRate (warning)

`rate(redis_daily_limit_fallback_total[5m]) > 1` 5분. CB OPEN 까진 안 갔는데 fallback 이 자주 호출되는 상태. 1.4 의 전조라고 보는 게 안전해요. Redis latency 와 transfer-api ↔ Redis timeout 로그를 본 뒤 자원 여유 없으면 스케일 업.

---

### 1.6 TransferP95LatencyHigh (warning)

송금 API p95 > 500ms 5분. 원인 후보 셋을 패널 보고 갈라요.

- **DB 락 경합**: 동일 계좌 동시 송금이 늘었을 때. `pg_stat_activity` 의 lock waiting 비율.
- **Redis 지연**: Redis Fallback 패널이 같이 튀면 거의 확정.
- **Kafka producer 지연**: outbox 동기 send timeout. producer rate 가 떨어지고 PENDING 이 증가하면 의심.

DB 락 경합이면 부하 자체를 줄이기 전엔 즉시 조치가 없다. 캠페인성 트래픽 중이면 throttling 검토. Kafka producer 지연은 outbox fallback 이 흡수하므로 사용자 영향은 제한적이지만 5분 이상 지속되면 1.9 절차로.

영구 조치: 계좌별 분산 락 또는 sharding ([MULTI-INSTANCE 4](MULTI-INSTANCE.md)). 다만 현재 DB 락이 충분한지 측정부터.

---

### 1.7 FdsAnalyzeP95Slow (warning)

룰 평가 p95 > 300ms 5분. 의심 둘 — `RecentTransferCountQueryAdapter` 의 DB 쿼리 지연(인덱스 떨어졌거나 통계 stale), ML 어댑터 지연(현재 NoOp 이라 0ms 여야 정상). Kibana 의 룰별 latency breakdown 확인 후 `EXPLAIN ANALYZE` 로 plan 점검.

---

### 1.8 TransferApi5xxHigh (critical)

5xx 비율 > 1% 5분. 사용자에게 직접 보이는 자리. 가장 다급한 알림 중 하나예요.

의심 원인 — DB connection pool 고갈(slow query 가 pool 을 잡고 있는 경우가 많다), 직전 배포의 NPE / IllegalArgument(5xx breakdown 첫 30초가 답), Kafka·Redis 동기 실패가 사용자 응답까지 번진 경우.

임시 조치: 직전 배포 원인이면 rollback. DB pool 고갈이면 pool size 임시 증가는 응급조치는 되지만 slow query 가 진짜 원인일 가능성을 가린다. 외부 의존성이 원인이면 1.1~1.7 의 해당 절차로.

영구 조치: 알림 채널 어댑터 자체가 부재 ([LIMITATIONS 1.3](LIMITATIONS.md)). 5xx 가 떴을 때 자동 회복 / 자동 사용자 통보가 없다 — P0.

---

### 1.9 OutboxLagGrowing (high)

`outbox_pending_count > 1000` 3분. Relay 가 송금 속도를 못 따라가는 상태. 1.3 의 부분 사례거나 그냥 트래픽이 늘어난 거다.

Relay 인스턴스 셋이 다 살아있는지 확인 후, 다 살아 있는데 lag 가 늘면 폴링 빈도(1s)와 배치 크기(1K) 가 트래픽에 부족한 상태. 같은 임계에서 `PhaseEvolution_OutboxPendingHigh` 가 같이 떠 있다면 거의 확정. EVOLUTION-ROADMAP 의 Phase 2(200ms, 5K) 적용 시점.

---

### 1.10 OutboxOldestPendingTooOld (critical)

1분 이상 발행 대기, 2분 지속. 1.9 보다 심각 — 그냥 느린 게 아니라 **어떤 row 가 잡혀서 안 풀리는 상태** 일 가능성.

의심 원인 — Kafka producer 가 죽어 모든 발행 실패, Relay 전원 down, `SENDING` 상태로 잡힌 row 가 graceful shutdown 부재로 풀리지 않음 ([MULTI-INSTANCE 8](MULTI-INSTANCE.md)).

임시 조치: Kafka 원인이면 1.12 절차로. `SENDING` 잠김이면 수동으로 status 를 PENDING 으로 되돌리는 SQL — 이건 운영에서 한 번도 안 해봤어요. 잘못 돌리면 중복 발행이라 trace 확인 후 신중하게.

영구 조치: `SENDING → PENDING` 자동 복구 스케줄러 + graceful shutdown 설정.

---

### 1.11 JvmHeapHigh / JvmGcTimeHigh (warning)

heap > 85%, 또는 GC pause rate > 0.1/s. 같은 서비스의 latency 패널이 같이 안 튀면 일단 관찰. GC pause 가 latency 를 끌고 올라가는 패턴이 보이면 위험. heap dump 확보 후 `-Xmx` 일시 1.5배. leak 인지 단순 트래픽 증가인지는 dump 분석으로. heap dump on OOM 자동화는 1.1 의 영구 조치와 같은 PR.

---

### 1.12 KafkaConsumerLagHigh (high)

consumer lag > 10000 5분. FDS 분석이 뒤처지고 룰 위반 거래의 차단 시점이 지연됨. 토픽 partition 부족(현재 3), `num.stream.threads=2` 부족, consumer 인스턴스 단일이 후보.

일시적 spike 면 그냥 catch-up. 30분 이상 지속되면 partition 추가(`kafka-topics --alter --partitions N`) — 단 partition 만 늘리면 ordering 가정이 깨질 수 있는 키가 없는지 먼저 본다. State Store 인스턴스 이전 비용도 같이 고려 ([MULTI-INSTANCE 3.2](MULTI-INSTANCE.md)).

---

### 1.13 DlqDeadLetterPresent / DlqDeadLetterHigh

`dlq_dead_letter_count > 0` 10분(warning), `> 100` 5분(critical). cool-down(60분) 안에 DEAD_LETTER 가 누적되는 건 일시적 장애가 아니라 **메시지 자체에 문제가 있을 가능성** — 스키마 호환성 깨짐, 또는 영구 실패.

DLQ 테이블에서 최근 row 몇 개를 직접 본다. 같은 에러가 반복되면 거의 확정. 스키마 깨짐이면 Schema Registry 호환성 확인 후 producer rollback. 메시지 포맷이 잘못된 경우 DLQ row 격리 후 manual.

DLQ Worker 가 아직 README 만 있고 실제 구현이 비어 있어요 ([LIMITATIONS 1.3](LIMITATIONS.md)). 이 절차는 가설이다 — P0.

---

### 1.14 PhaseEvolution_* (warning / critical / info)

`#fds-roadmap` 채널로 별도 라우팅. incident 가 아니라 **다음 단계로 진화할 시점이 됐다는 신호**. 즉시 조치보다 EVOLUTION-ROADMAP / ADR 갱신이 더 중요.

- `OutboxPendingHigh` 1h, `OutboxLagHigh` 5m → Phase 1→2 (폴링 튜닝).
- `ConsiderCDC` 10m, `DBCpuFromPolling` 30m → Phase 2→3 (Debezium CDC PoC). E5 실험 패키지 실행.
- `ConsiderTxProducer` 30m → Phase 3→4 (Transactional Producer).
- `KafkaConnectSlotLag` → Phase 3 운영 중 sustained lag.

알림을 incident 로 다루지 말고 ADR-010 의 트리거 충족 여부만 확인 후 다음 phase 의 PoC PR 을 생성한다. 후속 작업은 보통 주 단위.

---

## 2. 에스컬레이션 기준

| 조건 | 다음 사람 |
|---|---|
| critical 30분 미해결 | 백엔드 리드 + 인프라 담당 |
| high 2시간 미해결 | 백엔드 리드 |
| `phase_transition=*` 24h 지속 | 백엔드 리드 + 제품(다음 phase 일정) |
| 송금 실패율 > 5% | incident commander 즉시 지정, CS 통보 |

incident commander 지정은 슬랙 스레드 첫 메시지에 "commander: @누구" 한 줄이면 충분. 결정권자가 명확해야 30분이 깨끗하게 흐른다. 이 표는 운영팀 합의가 아직 안 된 상태에서 적은 가이드라인이에요. on-call 로테이션 도입 시점에 다시 다듬는다.

---

## 3. 자주 쓰는 명령

docker compose 기준(로컬/스테이징). Kubernetes 환경은 가설로 적었어요 — 실제 클러스터 운영 경험은 없다.

```bash
# 컨테이너 상태 / 로그
docker compose ps
docker compose logs --tail=200 transfer-api
docker compose logs -f --since=5m fds-api

# 재시작은 마지막 카드예요
docker compose restart transfer-api

# 헬스체크
curl -s http://localhost:8080/actuator/health | jq
curl -s http://localhost:8081/actuator/health | jq    # fds-api

# Outbox / DLQ 상태
docker compose exec postgres psql -U app -d transentia \
  -c "SELECT status, COUNT(*) FROM transfer_events GROUP BY status;"
docker compose exec postgres psql -U app -d transentia \
  -c "SELECT status, COUNT(*) FROM dlq_events GROUP BY status;"

# Redis
docker compose exec redis redis-cli ping
docker compose exec redis redis-cli --latency

# Kafka lag
docker compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group fds-stream-processor --describe
```

Kubernetes(가설):

```bash
kubectl -n transentia get pods
kubectl -n transentia logs -f deploy/transfer-api --tail=200
kubectl -n transentia rollout restart deploy/transfer-api
kubectl -n transentia rollout undo deploy/transfer-api
```

---

## 4. 사후 회고 템플릿

incident 종료 직후 24시간 안에. 길게 쓸 필요 없어요. 다음 사람이 같은 알림을 다시 받았을 때 빨리 읽을 수 있는 길이로.

```markdown
# Incident YYYY-MM-DD HH:MM — {알림명}

## 타임라인
- HH:MM  알림 수신
- HH:MM  첫 진단 (어느 패널을 보고 무엇이라고 판단했는가)
- HH:MM  임시 조치 적용
- HH:MM  사용자 영향 종료
- HH:MM  완전 회복

## 영향
- 사용자 영향 시간: N 분
- 실패한 송금 건수 (transactions 쿼리로 산출)
- 사후 정정 필요 여부 (회계, fraud_detections backfill 등)

## 5-Why
1. 왜 알림이 떴나
2. 왜 그 상태가 됐나
3. 왜 사전에 막지 못했나
4. 왜 더 빨리 감지하지 못했나
5. 시스템 조건 중 어디를 바꾸면 재발하지 않나

## 후속 작업
- [ ] PR: {코드 fix}
- [ ] ADR 갱신: {결정 변경 / 트리거 기준 조정}
- [ ] LIMITATIONS / FAILURE-MODES 의 미검증 항목을 검증됨으로 갱신
- [ ] 알림 임계값 / for 기간 조정
- [ ] 운영 대시보드 패널 추가
```

5-Why 는 사람 탓이 아니라 시스템 조건에 닿을 때까지 내려가는 게 원칙. "X 가 실수했다" 에서 멈추면 같은 사고가 재발한다.

---

## 5. 이 문서의 한계

위에 적은 임시 조치의 상당수는 **로컬 docker compose 에서만 검증됐거나 아예 검증되지 않았다**. 검증 안 된 자리만 추려 두면 — transfer-relay 1대 KILL -9 후 STUCK 이벤트 복구(0회), Kafka 브로커 강제 종료 후 producer/consumer 복구 시간(0회), Redis 응답 지연 1초 시 CB 동작(Toxiproxy 필요, 미수행), State Store 손상 후 changelog replay 시간(1주일치 기준 가설), DLQ Worker 자체가 미구현이라 1.13 의 절차는 "있어야 할 것" 으로 적었다.

이 갭들은 후속 chaos testing (P3, [LIMITATIONS 2](LIMITATIONS.md)) 으로 채운다. 실제 incident 가 나면 그때마다 해당 항목을 "가설 → 검증됨" 으로 갱신한다.
