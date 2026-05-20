# ADR-005: 일일 송금 한도 검증을 위한 Redis 캐시

- **Status**: Accepted
- **Date**: 2026-01-08
- **Deciders**: Backend Lead
- **Tags**: cache, redis, performance, consistency, fds

---

## Context

송금 처리 시 매 건마다 **사용자의 당일 누적 송금액** 을 확인해야 한다. 일일 한도(예: 1,000만 원)를 초과하면 송금을 거부해야 한다.

이는 다음과 같은 SQL 로 표현된다.

```sql
SELECT COALESCE(SUM(amount), 0) AS daily_sum
FROM transactions
WHERE sender_id = :userId
  AND created_at >= TODAY()
  AND status = 'COMPLETED';
```

### 문제

송금 한 건당 위 쿼리가 한 번 실행된다. TPS 가 늘어나면 DB 부하가 누적된다.

- **`transactions` 테이블 풀스캔**: 인덱스를 잘 걸어도 사용자별 일별 집계는 BMP scan 비용이 든다.
- **활성 사용자 N 명 × 송금 빈도 M 건/시간** = `N×M` 회의 SUM 쿼리.
- 송금 트랜잭션의 `@Transactional` 안에서 이 쿼리가 실행되면 락 보유 시간이 늘어난다.

### 추가 요구사항

- **일자 경계**: 한국 시간(KST) 자정 기준으로 누적이 리셋되어야 한다.
- **정합성**: 한도 초과 송금이 들어가서는 안 된다 (under-estimate 금지).
- **가용성**: 캐시가 죽어도 송금 서비스 자체는 계속 동작해야 한다 (현재는 캐시 우선 정책으로 channel fallback 미구현, 향후 과제).

---

## Decision

**Redis `INCRBY` + TTL 기반 일일 누적 캐시를 도입한다. 키는 `daily:transfer:{userId}:{yyyyMMdd}`, TTL 은 26시간.**

### 1. 캐시 키 / 값

```text
KEY:   daily:transfer:1234567890:20260518
VALUE: "5500000"   (long, 누적 송금액)
TTL:   26h
```

### 2. 코드 구조

`services/transfer/infra/.../RedisDailyTransferAmountCacheAdapter.kt`

```kotlin
@Component
class RedisDailyTransferAmountCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
) : DailyTransferAmountCachePort {

    override fun getTodayAmount(userId: Long): Long {
        val value = redisTemplate.opsForValue().get(keyFor(userId)) ?: return 0L
        return value.toLongOrNull() ?: 0L
    }

    override fun addTodayAmount(userId: Long, amount: Long): Long {
        val key = keyFor(userId)
        val ops = redisTemplate.opsForValue()
        val newValue = ops.increment(key, amount) ?: amount
        if (redisTemplate.getExpire(key) < 0) {
            redisTemplate.expire(key, TTL)
        }
        return newValue
    }

    private fun keyFor(userId: Long): String {
        val today = LocalDate.now(KST).format(YYYYMMDD)
        return "daily:transfer:$userId:$today"
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TTL: Duration = Duration.ofHours(26)
    }
}
```

### 3. 호출 흐름

`TransactionService.createTransfer()` (`services/transfer/application/.../TransactionService.kt:31`)

```kotlin
@Transactional
override fun createTransfer(command: TransferRequestCommand): TransferResponseCommand {
    val (sender, receiver) = loadUsers(command)
    val amount = command.amount()

    // 1) 검증 단계 - 누적 조회
    val dailyAccumulated = dailyTransferAmountCache.getTodayAmount(sender.id.value)
    TransferValidator.validate(sender, receiver, amount, dailyAccumulated)

    // 2) 잔액 차감 + 트랜잭션 저장
    sender.accountBalance.withdrawOrThrow(amount)
    val savedTransaction = transactionRepository.save(transaction)

    // 3) 누적치 갱신 (over-estimate 가 under 보다 안전)
    dailyTransferAmountCache.addTodayAmount(sender.id.value, amount.money.rawValue)

    eventPublisher.publishEvent(transaction.complete())
    return TransferResponseCommand.from(savedTransaction)
}
```

### 4. 일관성 전략: Over-estimate Safe

핵심 결정: **트랜잭션 커밋 전에 캐시를 INCRBY** 한다.

```text
시점 t1:  cache.getTodayAmount(userId)  -> 500만원
시점 t2:  검증 (송금 100만 -> 통과)
시점 t3:  잔액 차감 + transactions INSERT
시점 t4:  cache.addTodayAmount(userId, 100만)  -> 600만
시점 t5:  @Transactional 커밋
시점 t6:  ApplicationEvent 발행 -> Kafka
```

만약 t5 에서 DB 롤백이 일어나면 캐시는 100만 원 더 높게 남는다 (over-estimate). 이 경우 다음 송금은 “더 작은 한도”로 검증된다.

> **이게 왜 안전한가?**
> - Under-estimate(캐시가 실제보다 낮음) → 한도 초과 송금이 통과될 수 있다. **위험**.
> - Over-estimate(캐시가 실제보다 높음) → 정상 송금이 거부될 수 있다. **재시도 가능 + 보수적 안전.**
>
> 금융 도메인에서 “덜 보내는 실수” 는 “더 보내는 실수” 보다 항상 낫다.

### 5. TTL = 26시간

- KST 자정 + 2시간 여유.
- DST(서머타임) 가 없는 한국 기준이지만, 운영 중 일자 경계 직후 캐시 미스를 방지하기 위해 여유 둠.
- 자정 직후 키가 자동 만료되도록 함으로써 "어제 키" 가 누적되지 않는다.

---

## Consequences

### Positive

- **DB 부하 제거**: 송금 한 건당 SUM 쿼리 1회 → Redis GET 1회. 응답 시간 50ms → 1ms.
- **단순한 구현**: Redis 5줄 코드로 끝남. Lua script 등 불필요.
- **TTL 자동 정리**: 일자 경계 처리가 키 만료로 자동 해결.
- **Over-estimate 안전**: 트랜잭션 롤백 시에도 한도가 “덜 보낼 방향” 으로 어긋난다.
- **WSGenerationKafkaTransferEventPublisher**: 캐시 갱신은 in-process 동기 호출. 트랜잭션 외부 의존성 추가 없음.

### Negative

- **Redis 장애 시 송금 차단**: 현재 fallback 미구현. Redis 가 다운되면 `getTodayAmount` 실패 → 송금 실패. **개선 필요(TODO):** Redis 장애 시 DB 폴백.
- **롤백 시 캐시 부정확**: 송금 실패가 잦으면 캐시가 영속적으로 over-estimate 누적. 24시간 후 TTL 만료 시 리셋되지만 그 사이 정상 송금이 거부될 수 있다.
- **Lua atomicity 미사용**: `INCRBY` 와 `EXPIRE` 가 2 회 round-trip. 동시성 측면에서는 INCRBY 가 atomic 하므로 문제없지만, EXPIRE 호출이 누락될 가능성이 있다 (드물지만 가능). **개선 후보: `SET key value EX 93600 NX` + `INCRBYFLOAT` 조합 Lua script.**
- **TTL 26h 의 누적 영향**: 자정 직후 키가 만료되기 전 중복 가능. 실제로는 26h 만료가 24h 보다 짧은 시점에 만료되므로 영향 없음. 단지 운영자가 “왜 26?” 을 이해해야 한다.

### Neutral

- **분산 일관성**: Redis 가 master-replica 인 경우 replica 에서 읽으면 stale 가능. 현재는 master 만 읽음 (`StringRedisTemplate` 기본).
- **모니터링 의존**: `redis_keyspace_hits / misses` 와 `daily_transfer_*` 키 수를 모니터링해야 한다.

---

## Alternatives Considered

### A. DB 집계 쿼리 매번 실행

```sql
SELECT COALESCE(SUM(amount), 0)
FROM transactions
WHERE sender_id = :userId
  AND created_at >= :startOfDay
```

- **장점**: 캐시 동기화 문제 없음. 항상 정확.
- **단점**: TPS 1,000 → 매초 1,000 SUM 쿼리. `transactions` 테이블 인덱스(`sender_id`, `created_at`) 의 BMP scan 비용.
- **기각 사유**: 송금 응답 시간 50ms → 1ms 차이가 사용자 경험과 처리량에 직접 영향.

### B. Materialized View

```sql
CREATE MATERIALIZED VIEW daily_transfer_summary AS
SELECT sender_id, DATE(created_at) AS day, SUM(amount) AS total
FROM transactions
WHERE created_at >= NOW() - INTERVAL '7 days'
GROUP BY sender_id, DATE(created_at);
```

- **장점**: DB 안에서 일관성 보장. ACID.
- **단점**:
  - PostgreSQL MV 는 `REFRESH MATERIALIZED VIEW` 가 필요 (자동 갱신 불가, 또는 CONCURRENTLY 옵션 시 락).
  - 실시간성 부족. REFRESH 주기가 1분이면 1분 동안 stale.
- **기각 사유**: 실시간 한도 검증과 충돌.

### C. ElastiCache (AWS managed Redis)

- **장점**: managed 운영. 자동 fail-over.
- **단점**:
  - **현재 단계에서는 AWS 이전이 없음**. 로컬 Docker Redis 면 충분.
  - 마이그레이션 시 본 ADR 의 코드 변경 없이 endpoint 만 교체 가능.
- **기각 사유**: 본 채택안과 충돌하지 않음. 호스팅 결정은 별도 ADR.

### D. Memcached

- **장점**: 더 가볍고 빠름.
- **단점**:
  - `INCRBY` 지원하지만 데이터 구조가 단순.
  - TTL 기반 만료는 동일.
  - **Redis 가 이미 다른 도메인(FDS 룰 캐시, 세션 등)에 사용 중**. 인프라 단순화 위해 Redis 통일.
- **기각 사유**: 인프라 단순화 우선.

### E. In-Process Cache (Caffeine 등)

- **장점**: 가장 빠름. 네트워크 0.
- **단점**:
  - **인스턴스 간 공유 불가**. 송금 API 가 N 대로 스케일아웃되면 인스턴스마다 캐시가 다르다.
  - 한 사용자의 송금이 인스턴스 A 에 부착되어 있다가 인스턴스 B 로 라우팅되면 한도 검증 실패.
- **기각 사유**: 멀티 인스턴스 환경 비호환.

---

## Trade-offs

| 축 | Redis INCRBY (채택) | DB SUM | Materialized View | In-Process |
|---|---|---|---|---|
| **응답 시간** | ~1ms | 30~50ms | <1ms (단, 갱신 필요) | <0.1ms |
| **정확성** | Over-estimate (안전) | 정확 | Stale (REFRESH 주기) | 정확 |
| **DB 부하** | 없음 | 매 송금마다 | REFRESH 시 spike | 없음 |
| **장애 영향** | Redis 다운 → 송금 차단 | 없음 | 없음 | 인스턴스별 격리 |
| **멀티 인스턴스** | 호환 | 호환 | 호환 | 비호환 |
| **구현 복잡도** | 저 | 최저 | 중 (스케줄러) | 저 |

---

## Open Questions / TODO

- **Redis Sentinel/Cluster 도입**: 현재 단일 노드. 운영 환경 SLA 에 따라 결정.
- **Fallback 정책**: Redis 다운 시 DB SUM 으로 fallback. 응답 시간 저하 vs 가용성 트레이드오프.
- **음수 캐시 정정**: 송금 취소(환불) 발생 시 캐시는 감소시키지 않는다 (over-estimate 유지). 일자 경계 후 자동 리셋 의존. 향후 환불이 잦아지면 재검토.

---

## References

### 코드 경로

- 포트 정의: `services/transfer/application/src/main/kotlin/.../required/DailyTransferAmountCachePort.kt`
- Redis 어댑터: `services/transfer/infra/src/main/kotlin/io/github/hyungkishin/transentia/infra/redis/RedisDailyTransferAmountCacheAdapter.kt`
- 호출 위치: `services/transfer/application/src/main/kotlin/io/github/hyungkishin/transentia/application/TransactionService.kt:38, 57`
- application.yml: `services/transfer/instances/api/src/main/resources/application.yml`

### 외부 자료

- Redis Documentation, "INCRBY", "EXPIRE", "Atomic operations"
- Martin Kleppmann, *Designing Data-Intensive Applications* (2017), Ch. 9 - Consistency models
- Pat Helland, "Building on Quicksand" — over-estimate 안전 디자인

---

## Related ADRs

- ADR-001: Hexagonal Architecture (Port/Adapter 패턴으로 Redis 격리)
- ADR-002: Transactional Outbox (캐시 갱신과 트랜잭션 커밋 순서 결정의 근거)
