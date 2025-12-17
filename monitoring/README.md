# Observability Stack 설정 가이드

## 개요

FDS 시스템의 관찰 가능성(Observability)을 위한 모니터링 스택입니다.

**구성 요소**
- Spring Boot Actuator: 애플리케이션 메트릭 수집
- Micrometer Prometheus: 메트릭 포맷 변환
- Prometheus: 메트릭 수집 및 저장
- Grafana: 메트릭 시각화

---

## 빠른 시작

### 1. 인프라 시작

```bash
# Prometheus + Grafana 포함 전체 인프라 실행
docker-compose up -d postgres kafka prometheus grafana

# 상태 확인
docker-compose ps
```

### 2. 애플리케이션 시작

```bash
# Transfer API 실행 (8080)
./gradlew :services:transfer:instances:api:bootRun

# Transfer Relay 3대 실행 (docker-compose로 실행 권장)
# FDS API 실행 (8082)
./gradlew :services:fds:instances:api:bootRun
```

### 3. 접속 정보

| 서비스 | URL | 인증 정보 |
|--------|-----|----------|
| Transfer API Actuator | http://localhost:8080/actuator | - |
| FDS API Actuator | http://localhost:8082/actuator | - |
| Prometheus | http://localhost:9090 | - |
| Grafana | http://localhost:3000 | admin / admin |

---

## Actuator 엔드포인트

### Health Check

```bash
# Transfer API
curl http://localhost:8080/actuator/health

# FDS API
curl http://localhost:8082/actuator/health
```

**응답 예시**
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

### Prometheus 메트릭

```bash
# Transfer API Prometheus 메트릭
curl http://localhost:8080/actuator/prometheus

# FDS API Prometheus 메트릭
curl http://localhost:8082/actuator/prometheus
```

---

## 주요 메트릭

### JVM 메트릭

| 메트릭 | 설명 | PromQL 예시 |
|--------|------|------------|
| `jvm_memory_used_bytes` | 힙/논힙 메모리 사용량 | `jvm_memory_used_bytes{area="heap"}` |
| `jvm_gc_pause_seconds` | GC pause time | `rate(jvm_gc_pause_seconds_sum[5m])` |
| `jvm_threads_live_threads` | 활성 스레드 수 | `jvm_threads_live_threads` |

### HikariCP (DB 커넥션 풀)

| 메트릭 | 설명 | 임계값 |
|--------|------|--------|
| `hikaricp_connections_active` | 활성 커넥션 수 | < maximum_pool_size |
| `hikaricp_connections_idle` | 유휴 커넥션 수 | >= minimum_idle |
| `hikaricp_connections_pending` | 대기 중 요청 수 | 0 (이상적) |
| `hikaricp_connections_timeout_total` | 타임아웃 발생 수 | 0 (이상적) |

### Kafka Producer

| 메트릭 | 설명 |
|--------|------|
| `kafka_producer_record_send_total` | 전송된 레코드 수 |
| `kafka_producer_record_error_total` | 전송 실패 수 |
| `kafka_producer_batch_size_avg` | 평균 배치 크기 |

### Kafka Consumer (FDS)

| 메트릭 | 설명 |
|--------|------|
| `kafka_consumer_records_consumed_total` | 소비된 레코드 수 |
| `kafka_consumer_fetch_manager_records_lag` | Consumer lag |

### 커스텀 비즈니스 메트릭

```kotlin
// 예시: Outbox Relay 처리량
outbox_relay_processed_total{partition="0"}
outbox_relay_failed_total{partition="0"}
```

---

## Prometheus 쿼리 예시

### 1. DB 커넥션 풀 사용률

```promql
# 활성 커넥션 비율
(hikaricp_connections_active / hikaricp_connections_max) * 100

# 대기 중인 요청 있는지 확인
hikaricp_connections_pending > 0
```

### 2. GC 영향 분석

```promql
# GC로 인한 초당 정지 시간 (ms)
rate(jvm_gc_pause_seconds_sum[1m]) * 1000

# GC 빈도
rate(jvm_gc_pause_seconds_count[1m])
```

### 3. 스레드 풀 상태

```promql
# 활성 스레드가 최대치에 가까운지 확인
jvm_threads_live_threads / jvm_threads_peak_threads > 0.8
```

### 4. Kafka 처리량

```promql
# 초당 전송 레코드 수
rate(kafka_producer_record_send_total[1m])

# Consumer lag (지연)
kafka_consumer_fetch_manager_records_lag
```

---

## Grafana 대시보드 설정

### 1. Prometheus 데이터소스 추가

1. Grafana 접속: http://localhost:3000 (admin/admin)
2. Configuration > Data sources > Add data source
3. Prometheus 선택
4. URL: `http://prometheus:9090`
5. Save & Test

### 2. 추천 대시보드 Import

**JVM (Micrometer) Dashboard**
- Dashboard ID: `4701`
- https://grafana.com/grafana/dashboards/4701

**Spring Boot Statistics**
- Dashboard ID: `6756`
- https://grafana.com/grafana/dashboards/6756

**HikariCP**
- Dashboard ID: `9528`
- https://grafana.com/grafana/dashboards/9528

### 3. 커스텀 패널 예시

**DB 커넥션 풀 모니터링**
```
Panel: Time series
Query A: hikaricp_connections_active{application="transfer-api"}
Query B: hikaricp_connections_idle{application="transfer-api"}
Query C: hikaricp_connections_pending{application="transfer-api"}
```

**Outbox Relay 처리량 (파티션별)**
```
Panel: Graph
Query: rate(outbox_relay_processed_total[1m])
Legend: {{instance}}
```

---

## 성능 임계점 실험 가이드

### 목표

멘토링 피드백에서 요구한 **정량적 지표 기반 병목 분석**

### 실험 절차

1. **부하 없는 상태에서 베이스라인 측정**
   - JVM Heap 사용량
   - DB 커넥션 수
   - GC pause time

2. **점진적 부하 증가**
   ```bash
   # 500 TPS
   k6 run --vus 50 --duration 5m load-test.js
   
   # 1000 TPS
   k6 run --vus 100 --duration 5m load-test.js
   
   # 1500 TPS
   k6 run --vus 150 --duration 5m load-test.js
   ```

3. **각 구간에서 관찰할 지표**
   - Response time P95, P99
   - DB 커넥션 대기 시간
   - GC pause 증가율
   - CPU 사용률
   - Kafka consumer lag

4. **병목 지점 식별**
   - 어느 TPS에서 처음으로 응답 시간이 급증하는가?
   - 어떤 리소스가 먼저 포화되는가?
   - 스레드 풀? DB 커넥션? Kafka?

5. **결과 문서화**
   ```
   - 500 TPS: 정상 (P95 < 100ms)
   - 1000 TPS: 정상 (P95 < 150ms)
   - 1500 TPS: DB 커넥션 대기 발생 (임계점)
   - 결론: HikariCP maximum-pool-size 확장 필요
   ```

---

## 알람 설정 (TODO)

Prometheus Alertmanager 연동 예정

**주요 알람 규칙**
- DB 커넥션 풀 고갈 (> 90%)
- Consumer lag 임계치 초과 (> 1000)
- GC pause time 과다 (> 1s)
- 에러율 급증 (> 5%)

---

## 트러블슈팅

### Prometheus가 메트릭을 수집하지 못함

```bash
# 1. Actuator 엔드포인트 확인
curl http://localhost:8080/actuator/prometheus

# 2. Prometheus targets 상태 확인
# http://localhost:9090/targets

# 3. Prometheus 로그 확인
docker logs transfer-prometheus
```

### Grafana에서 데이터가 안 보임

1. Prometheus 데이터소스 연결 확인
2. 쿼리 시간 범위 확인 (Last 15 minutes)
3. 메트릭 이름 오타 확인

---

## 다음 단계

- [] Actuator + Prometheus 설정 완료
- [] Grafana 대시보드 구성
- [] 부하 테스트 + 임계점 측정
- [] 알람 규칙 설정
- [] 운영 환경 구성 (LB + 이중화)

---

## 참고 자료

- [Spring Boot Actuator 공식 문서](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer Prometheus](https://micrometer.io/docs/registry/prometheus)
- [Prometheus 쿼리 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana 대시보드 Gallery](https://grafana.com/grafana/dashboards/)
