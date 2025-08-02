# Kotest 도입 배경 및 가이드

## 왜 JUnit만으로는 충분하지 않은가

JUnit은 성숙하고 훌륭한 테스트 프레임워크다.  
그러나 도메인 주도 설계를 따르고 있다면 테스트는 단순한 코드 검증을 넘어  
**도메인 규칙의 명세서**가 되어야 한다.

---

## Kotest 도입 목적

Kotest는 테스트를 더 “편하게” 하기 위한 도구가 아니다.  
테스트를 통해 **규칙을 설명하고, 문서화하며, 설계의 일부로 끌어들이기 위한 표현 수단**이다.

| 항목 | JUnit | Kotest |
|------|-------|--------|
| 테스트 이름 표현 | @Test fun \`설명\` | "설명" { } |
| 테스트 구조 | 함수 기반 | 명세 기반 (Spec) |
| 표현력 | assert 중심 | DSL 기반, matcher 조합 표현력 풍부 |
| 실패 메시지 | 제한적 | 설명적이고 구체적인 실패 메시지 |
| 시나리오 표현 | 보일러플레이트 많음 | Given/When/Then 구조화 지원 |
| 도메인 규칙 표현 | 제한적 | 구조적으로 자연스럽게 표현 가능 |

---

## 예제 비교

### JUnit 스타일

```kotlin
@Test
fun `소수점 포함 금액은 문자열로 변환된다`() {
    val money = Money.fromDecimalString("123.45")
    assertEquals("123.45", money.toString())
}
```

### Kotest 스타일

```kotlin
"소수점 포함 금액은 문자열로 변환된다" {
    val money = Money.fromDecimalString("123.45")
    money.toString() shouldBe "123.45"
}
```

---

## 도입 효과

- 테스트 코드 자체가 도메인 규칙의 설명서 역할을 한다
- 명확한 문장 기반 테스트 이름으로 QA, 신규 개발자, PM과의 소통이 쉬워진다
- 복잡한 조건 검증에서 가독성과 유지보수성이 뛰어나다
- 실패 원인을 찾기 쉬운 메시지 구조

---

## 도입 전략

1. 기존 JUnit 코드는 그대로 유지 가능하다. Kotest는 JUnit5 플랫폼 위에서 작동한다.
2. 도메인 규칙이 중요한 모듈 (송금)부터 도입한다.

---

## Gradle 설정 (build.gradle.kts)

```kotlin
dependencies {
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

kotest-bom을 사용할 경우 버전 생략도 가능하다.

---

## 스타일

- StringSpec: "~~해야 한다"와 같은 규칙 표현에 적합
- BehaviorSpec: Given/When/Then 구조가 필요한 복잡한 흐름에 적합
- FunSpec: 함수 기반 구조가 익숙한 경우 적합

---

## 고민

> Q. JUnit으로도 충분한데 굳이 써야 하나?

A. 기능적으로는 JUnit으로 가능하다. 하지만 도메인 규칙을 설명하고 유지보수 가능한 테스트를 만들기 위해선 표현력이 중요한데, Kotest가 이를 더 잘 지원한다.

> Q. 기존 코드와 충돌하지 않나?

A. 전혀 충돌하지 않는다. Kotest는 JUnit5 기반 위에서 실행되며 공존 가능하다.

> Q. 학습 비용이 걱정된다.

A. StringSpec만 익혀도 기존 JUnit 사용자라면 바로 사용할 수 있다. 문법은 익숙하고 러닝커브는 작다.

---

## 결론

테스트는 단지 코드가 아니다.  
테스트는 규칙이며, 명세이며, 도메인의 일부다.  
Kotest는 이를 표현할 수 있는 더 나은 언어다.