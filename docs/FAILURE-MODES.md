# FAILURE-MODES — 컴포넌트별 장애 영향도와 회복 시나리오

> FMEA(Failure Mode and Effects Analysis) 의 간소화 형식.
> 컴포넌트가 죽을 때 **무엇이 멈추고, 무엇이 자동 회복되고, 무엇이 사람이 와야 하는지** 정리.

각 항목 포맷:

```
[컴포넌트] 장애 모드
- 감지 (어떤 alert / 메트릭이 켜지나)
- 즉시 영향 (사용자가 체감하는 것)
- 잠재 영향 (시간 지나면 번지는 것)
- 자동 회복 (시스템이 하는 일)
- 수동 복구 (사람이 하는 일)
- 비고 (테스트 됐나, LIMITATIONS)
```

---

## 1. 데이터 계층

### 1.1 PostgreSQL 단일 노드 down

- **감지**: `up{job="postgres"}` 미수집(현재 미스크랩, 추가 필요), HikariCP `connection-timeout` 폭증, `TransferApi5xxHigh` 알림.
- **즉시 영향**: 송금 API 100% 실패. 잔액 조회/송금 이력 전부 불가. **단일 장애점**.
- **잠재 영향**:
  - Outbox 발행 누적이 아예 안 됨(write 실패) — 데이터 손실 없음.
  - 이미 보낸 Kafka 이벤트는 FDS 가 정상 처리(FDS DB 가 같은 인스턴스라 같이 죽음).
- **자동 회복**: `restart: always` 정책 → 컨테이너 재시작. WAL replay.
- **수동 복구**:
  1. `docker compose logs postgres` 로 원인 (OOM / 디스크 풀 / 부팅 실패) 진단.
  2. 디스크 풀이면 `pg_data` 볼륨 확장.
  3. 복구 후 Outbox PENDING 모두 자동 발행.
- **비고**: **단일 노드 = 단일 장애점**. LIMITATIONS 1.2. Read replica + 자동 failover 미구성.

### 1.2 PostgreSQL 데드락

- **감지**: app 로그의 `deadlock detected`, p95 latency 폭증, `TransferP95LatencyHigh`.
- **즉시 영향**: 송금 일부 실패. 데드락 victim 은 트랜잭션 롤백.
- **자동 회복**: `loadUsers` 의 정렬된 락 획득 순서(`sender < receiver`)로 데드락 빈도 매우 낮음. PostgreSQL 이 자체 victim 선정.
- **수동 복구**: 빈도가 높으면 `pg_locks` + `pg_stat_activity` 로 잠금 트리 분석.
- **비고**: `e6b1744` 이후 비관 락 + 정렬 락. 데드락은 회피됐지만 측정 미수행.

### 1.3 PostgreSQL 디스크 풀

- **감지**: `pg_database_size` 게이지(현재 미수집), 쓰기 실패.
- **즉시 영향**: write 100% 실패. read 는 일시적으로 가능.
- **잠재 영향**: WAL 도 못 써서 PostgreSQL 자체가 정지.
- **수동 복구**:
  - `transfer_events` 의 발행 완료 row 정기 archive/삭제 미구현. LIMITATIONS.
  - flyway 의 `BATCH_*` 메타데이터 누적.
  - 볼륨 확장.

---

### 1.4 Redis 단일 노드 down

- **감지**: `RedisCircuitBreakerOpen` alert, `redis_daily_limit_fallback_total` 증가.
- **즉시 영향**: CB 가 OPEN → fallback 으로 `getTodayAmount=0`, `addTodayAmount` 누락. **일일 한도 검증이 over-permissive (fail-open)**.
- **잠재 영향**: 악의 사용자가 Redis 다운 시점에 한도 우회 가능. 회계 정정 필요.
- **자동 회복**:
  - CB half-open 단계 자동 전이(`wait-duration-in-open-state: 10s` 후).
  - Redis 컨테이너 `restart: unless-stopped`.
- **수동 복구**:
  1. Redis 복구 확인 (`redis-cli ping`).
  2. CB 가 자동 CLOSED 전이.
  3. **회계 검증** — Redis down 시점의 송금 거래 중 한도 초과한 게 있는지 `transactions` 테이블로 사후 검증.
- **비고**: fail-open vs fail-closed 정책은 비즈니스 결정. 현재 fail-open. LIMITATIONS 1.2.

### 1.5 Elasticsearch 단일 노드 down

- **감지**: `up{service="elasticsearch"}` (현재 미스크랩), Filebeat 로그 적재 실패 로그.
- **즉시 영향**: 신규 로그/AccessLog 가 Filebeat 버퍼에 쌓임. **검색 불가**. traceId 추적 일시 중단.
- **잠재 영향**:
  - Filebeat registry 가 진행 위치 보존 — 복구 시 누락 없이 backfill.
  - 디스크 폭발 위험 (Filebeat queue.disk).
- **자동 회복**: Filebeat 가 ES 회복 시 자동 retry. `discovery.type=single-node` 라 재시작만으로 동작.
- **수동 복구**: ES heap 부족이면 `ES_JAVA_OPTS` 조정. 인덱스 매핑 explosion 이면 인덱스 삭제 + ILM 도입.
- **비고**: ILM 비활성(LIMITATIONS 1.2.). 장기 운영 시 디스크 폭발.

---

## 2. 메시징 계층

### 2.1 Kafka 브로커 down (단일 브로커)

- **감지**: `up{job="kafka"}` 미스크랩, Producer 의 `acks=1` 실패, `KafkaConsumerLagHigh`.
- **즉시 영향**:
  - **transfer-api 동기 send 실패** → outbox 에 fallback row 적재.
  - **transfer-relay 발행 실패** → 같은 outbox row 재시도 누적.
  - **FDS consumer 정지** → lag 누적.
- **잠재 영향**: outbox PENDING 폭증, Kafka 가 길게 죽으면 (시간/일 단위) 디스크 풀 위험.
- **자동 회복**:
  - `restart: unless-stopped`.
  - 복구 후 Outbox 가 모든 PENDING 을 catch-up 발행. FDS consumer 가 lag 따라잡음.
- **수동 복구**:
  1. broker log 진단.
  2. `__consumer_offsets` 손상이면 토픽 보존 정책 점검.
  3. catch-up 중 부하 모니터링.
- **비고**: **RF=1 단일 브로커**(LIMITATIONS 1.2). 운영에선 RF=3 + 최소 3 브로커.

### 2.2 Kafka 토픽 partition 부족 → consumer lag

- **감지**: `KafkaConsumerLagHigh > 10K` 5분.
- **즉시 영향**: FDS 분석 지연. 룰 위반 거래가 일정 시간 후에야 차단됨.
- **자동 회복**: 없음. lag 줄이려면 partition/consumer thread 추가.
- **수동 복구**:
  1. partition 추가 (`kafka-topics --alter --partitions N`).
  2. `num.stream.threads` 조정.
  3. consumer 인스턴스 추가.
- **비고**: 현재 토픽 partition=3. 단일 FDS 인스턴스에서 처리.

### 2.3 Schema Registry down

- **감지**: producer/consumer 의 Avro 직렬화 실패 로그.
- **즉시 영향**: 새 스키마 발행 불가. 기존 스키마 캐시는 client side 에 남아 일정 시간 동작.
- **자동 회복**: `restart: unless-stopped`. 복구 후 신규 스키마 등록 가능.
- **수동 복구**: 스키마 호환성 깨진 PR 이 원인이면 rollback.

---

## 3. 애플리케이션 계층

### 3.1 transfer-api OOM (단일 인스턴스)

- **감지**: `JvmHeapHigh > 85%`, `JvmGcTimeHigh`, 컨테이너 exit code 137.
- **즉시 영향**: 진행 중 트랜잭션 abort. 송금 API 100% 실패 동안.
- **자동 회복**: `restart: unless-stopped` → 1분 내 재시작.
- **수동 복구**:
  - heap dump 확보 (`-XX:+HeapDumpOnOutOfMemoryError`) — 현재 미설정.
  - `JAVA_OPTS` 의 `-Xmx` 증가.
  - leak 의심이면 dependency 업데이트 후 long-run 측정.
- **비고**: **단일 인스턴스 단일 장애점**. 멀티 인스턴스 운영 미검증(LIMITATIONS 2.1).

### 3.2 transfer-relay 1대 down (3대 중 1대)

- **감지**: `TransferRelayDown{instance="relay-1"}`, `outbox_pending_count` 증가 가속.
- **즉시 영향**: MOD 파티셔닝 가정 깨짐 — `MOD(event_id, 3) = 1` 인 이벤트가 처리 안 됨.
- **잠재 영향**: 30분 후 해당 파티션의 outbox 가 수천 건 누적.
- **자동 회복**:
  - 단일 instance restart 면 자동 복구.
  - **다른 인스턴스가 죽은 인스턴스의 파티션을 자동 인수하지 않음** — 현재 정적 파티셔닝.
- **수동 복구**:
  1. 죽은 인스턴스 복구 OR
  2. `partition_count` 를 2로 줄이고 `instance_id` 재할당 (스케일링 도중 안전성 미검증 — LIMITATIONS 2.1).
- **비고**: 자동 rebalance 부재. 동적 파티셔닝(Kafka 컨슈머 그룹 위임)으로 교체 검토.

### 3.3 transfer-relay 3대 전부 down

- **감지**: `TransferRelayDown` × 3, `OutboxOldestPendingTooOld > 60s`.
- **즉시 영향**: outbox 발행 100% 정지. 진행 중 송금 정상 commit, FDS 분석만 정지.
- **자동 회복**: restart. 복구 후 모든 PENDING 일괄 발행. **이때 Kafka producer 가 부하 spike 일으킬 수 있음**.
- **수동 복구**:
  - 재시작 후 burst 발행으로 Kafka 가 잠시 흔들리면 producer rate limit 검토.
- **비고**: 운영에서 시뮬레이션 미수행.

### 3.4 FDS-API down

- **감지**: `FdsApiDown`, `KafkaConsumerLagHigh{client_id=fds-*}`.
- **즉시 영향**: 모든 송금이 분석 없이 통과. **이상거래 차단 0% 인 상태로 송금이 정상 처리됨**.
- **잠재 영향**: 사기 거래가 통과한 채 시간이 흐름. **회계 정정 필요**.
- **자동 회복**: restart. 복구 후 Kafka offset 부터 분석 재개 (단, 분석은 "사후" 가 됨 — 이미 송금은 완료).
- **수동 복구**:
  - 복구 후 `fraud_detections` 빈 구간 확인.
  - 의심 거래는 운영자가 수동 리뷰 (`OPS_AGENT` role).
- **비고**: **FDS 가 동기적으로 송금을 차단하는 구조가 아님**. 이는 의도된 설계 — FDS 가 정지해도 송금은 흐른다(가용성 우선). 사후 탐지 모델.

### 3.5 FDS Kafka Streams State Store 손상

- **감지**: app 로그의 RocksDB 예외, 컨테이너 재시작 loop.
- **즉시 영향**: 10분 윈도우 집계 정지.
- **자동 회복**: changelog topic 으로 자동 replay. **단, 1주일치 changelog 면 수십 분 소요**.
- **수동 복구**:
  - `<application-id>-<store>-changelog` 토픽 보존 정책 확인.
  - 영구 손상이면 changelog 재구축 (전체 토픽 reset + reprocess).
- **비고**: LIMITATIONS 2.2. 실 환경 미검증.

---

## 4. 인프라/관측성 계층

### 4.1 Prometheus down

- **감지**: Grafana 대시보드 빈 데이터.
- **즉시 영향**: 메트릭 수집 중단. 알림도 작동 안 함.
- **잠재 영향**: **알림 부재 자체가 더 큰 사고를 키움**. 다른 컴포넌트 장애가 감지 안 됨.
- **자동 회복**: restart.
- **수동 복구**: TSDB 손상이면 `--storage.tsdb.path` 재초기화.
- **비고**: Prometheus HA(이중화) 미구성. AlertManager 없음 → 알림 채널 0개.

### 4.2 Grafana down

- **감지**: 사람의 눈.
- **즉시 영향**: 대시보드 접근 불가. 메트릭은 살아있음.
- **자동 회복**: restart.
- **수동 복구**: provisioning 디렉터리 마운트 확인.

### 4.3 Filebeat down

- **감지**: `app-logs-*` 인덱스의 doc 증가율 0.
- **즉시 영향**: 신규 로그가 ES 로 안 감. 로그 파일은 디스크에 그대로.
- **자동 회복**: Filebeat 가 자체 retry. 컨테이너 재시작.
- **수동 복구**: Filebeat registry 손상이면 재초기화.

---

## 5. 네트워크 계층

### 5.1 Nginx down

- **감지**: 외부 health check.
- **즉시 영향**: 모든 API 접근 차단.
- **자동 회복**: restart.
- **비고**: 단일 Nginx — 단일 장애점. 운영에선 LB 이중화 필요.

### 5.2 transfer-api ↔ Kafka 네트워크 partition

- **감지**: producer timeout 로그.
- **즉시 영향**: 동기 send 실패 → outbox fallback.
- **자동 회복**: outbox relay 가 발행. 네트워크 회복 시 catch-up.
- **비고**: 가장 자주 발생할 시나리오. outbox 설계의 핵심 가치가 여기서 증명됨.

---

## 6. 우선순위 매겨진 회복탄력성 갭

| Priority | 갭 | 효과 |
|---|---|---|
| P0 | Kafka RF=3 + 3 브로커 | 단일 브로커 장애 무영향 |
| P0 | AlertManager + 알림 채널(Slack/PagerDuty) | 알림이 사람에게 도달 |
| P0 | DLQ Worker | 발행 실패가 영구 잔존하지 않게 |
| P1 | PostgreSQL Read replica + 자동 failover | 단일 노드 장애점 제거 |
| P1 | Redis Sentinel / Cluster + fail-closed 정책 옵션 | 일일 한도 무력화 방지 |
| P1 | transfer-api 멀티 인스턴스 + Nginx LB | API 가용성 |
| P2 | Outbox 자동 archive (발행 완료 row 삭제) | 디스크 안전 |
| P2 | ES ILM (Hot/Warm/Cold) | ES 디스크 안전 |
| P2 | Kafka Streams standby replica | State Store 복구 시간 단축 |
| P3 | Chaos testing (Toxiproxy / Litmus) | 실패 시나리오 사전 검증 |
| P3 | heap dump on OOM 자동화 | 사후 분석 |

---

## 7. 미검증 시나리오 (정직히 인정)

이 문서의 회복 절차 대부분은 **로컬 docker compose 에서 시뮬레이션 안 됨**. 실제 운영 환경에서 어떻게 동작할지는 가설이다.

- transfer-relay 1대 강제 KILL -9 후 outbox 처리 시간 측정 — 미수행.
- Kafka 브로커 강제 종료 후 producer/consumer 복구 시간 — 미수행.
- Redis 응답 지연 1초 시 CB 동작 — 미수행 (Toxiproxy 같은 도구 필요).
- ES 디스크 풀 시 Filebeat 동작 — 미수행.

이 갭들은 후속 PR 의 chaos testing 도입으로 검증한다 (P3).

---

## 관련 문서

- [LIMITATIONS.md](LIMITATIONS.md) — 운영 검증 부재 항목
- [MULTI-INSTANCE.md](MULTI-INSTANCE.md) — 멀티 인스턴스 엣지케이스
- [monitoring/prometheus-alerts.yml](../monitoring/prometheus-alerts.yml) — 알림 규칙
- [monitoring/grafana/.../transentia.json](../monitoring/grafana/provisioning/dashboards/transentia.json) — 대시보드
