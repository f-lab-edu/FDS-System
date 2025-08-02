# Money 설계의 정수 기반 접근 방식에 대한 기술적 배경

## 왜 Double, Float 을 사용할 수 없을까.

### 부동소수점 오차 문제

`Double`, `Float`은 IEEE 754 이진 부동소수점 방식에 기반한다.  
이 방식은 `0.1`, `0.2`, `0.3` 등의 10진 소수를 정확하게 표현하지 못한다.

```kotlin
val a = 0.1
val b = 0.2
val c = 0.3

println(a + b == c) // false
```

실제로 `a + b` 는 `0.30000000000000004` 가 되어 `c` 와 같지 않다.  
이로 인해 다음과 같은 문제가 발생한다:

- 분기 조건 오동작
- 테스트 실패
- 정산 오류
- 누적 계산 오차

금융/정산 도메인에서는 1원 단위 오차도 치명적이다.

---

## BigDecimal의 한계

### 정확성 측면에서 최고

`BigDecimal` 은 10진 기반 고정 정밀도 수치 연산을 제공한다.  
정확한 금액 계산에는 가장 안전한 타입이다.

```kotlin
val a = BigDecimal("0.1")
val b = BigDecimal("0.2")
val c = BigDecimal("0.3")

println(a + b == c) // true
```

### 성능저하, GC 비용 문제

하지만 `BigDecimal` 은 immutable 구조이기 때문에  
연산 시마다 새로운 인스턴스를 생성한다.

```kotlin
val a = BigDecimal("1000")
val b = a.add(BigDecimal("500")) // 새로운 인스턴스 반환
```

대량 계산이 반복되면 다음 문제가 발생한다.

- 힙 메모리 객체가 누적 생성됨
- GC(가비지 컬렉터) 부담 증가
- 실시간 시스템에서 지연(latency) 발생

---

## Long + scale 기반 설계

### 구조

- 금액을 `Long` 타입으로 표현
- 내부적으로 `scale = 2` (소수점 둘째 자리까지)
- 모든 연산은 `rawValue: Long` 기준으로 수행

예를 들자면,

```kotlin
val amount = Money.of(10_000)        // 10,000.00 원
val fee = amount.percentage(5)       // 500.00 원
val net = amount - fee               // 9,500.00 원
```

계산 흐름

- rawValue = 10_000 * 100 = 1_000_000
- 1_000_000 * 5 ÷ 100 = 50_000
- net.rawValue = 1_000_000 - 50_000 = 950_000

결과: `"9500.00"`

---

### 장점

- 정밀도 보장: 부동소수점 오차 없음
- 성능 우수: JVM 에서 Long 연산 최적화
- GC 부담 없음: 객체 생성 최소화
- 테스트 용이: 예상 가능한 정수값 비교
- RDB 매핑 용이: BIGINT 컬럼 하나로 처리 가능

---

## 동시성과 불변성

### BigDecimal은 동시성에 안전할까

정답 : `안전` 하다  
`BigDecimal` 은 immutable 구조이므로,  
연산 시 기존 객체를 변경하지 않고 항상 새로운 인스턴스를 반환한다.

여러 쓰레드가 같은 `BigDecimal` 을 참조하더라도 race condition 은 발생하지 않는다.

### 부담이 되는 이유

- 연산이 많아질수록 인스턴스가 증가
- GC 부하와 힙 사용량 증가
- 실시간 시스템에서 병목 발생

TestCode 로 성능 비교를 해보니, 약 열배의 성능차이가 나는것을 볼 수 있다.
> - Money (Long): 4 ms, result = 10000000
> - BigDecimal: 44 ms, result = 10000000.0
---

## 5. 결론

| 항목 | 평가 |
|------|------|
| Double / Float | 부동소수점 오차로 인해 금지 대상 |
| BigDecimal | 정확하지만 성능과 GC 부담 큼 |
| Long + scale | 정밀도와 성능을 모두 확보한 설계 방식 |

실무에서는 `Long + scale` 구조를 사용한 `Money` 값을  
도메인 객체로 정의하고 사용하는 것이 가장 바람직하다.
