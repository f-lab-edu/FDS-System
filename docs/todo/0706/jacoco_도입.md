# 최근 코드 품질에 대한 고민

## 바쁜 실무, 점점 줄어드는 사람 리뷰

요즘 개발자들의 현실은... 사바사 라지만 **대부분 정말 바쁘다**. 🤦‍♂️   
코드리뷰 문화가 정착되어 있다 하더라도, 사실상 **리뷰 자체가 형식적으로 흐르거나 생략되는 경우가 많다**.  
지인들만 봐도 아래와 같다:

- A: "예전엔 타 팀 코드도 보고, 리뷰도 많이 했는데 이제는... 걍 자기 일 하기도 벅차다."
- B: "커버리지 도구? Jacoco 써. GPT로 리뷰도 종종 하지."
- C: "야 살려줘.."
- D: "이게 회사냐...퇴사하고 싶다." ( 얜 좀 심각한 것 같다. )

심지어 너무 바쁜 나머지, 카카오톡 상태메시지가 `카톡 힘들어요, 문자 주세요`인 지인도 있다. 😅   

때문에, 형식적 리뷰가 많아지고 사람 리소스가 줄어드는 요즘엔 다음 영역들이 **이미 AI나 자동화로 넘어갔다고 본다.**

- 리펙토링 구조 제안
- 네이밍 피드백
- 함수 분리 판단

### 그렇다면 True 멘토님이 언급 주신 품질 체크는 무엇일까?

> **자동화 가능한 품질 검증** 영역이다.  
예를 들어 Jacoco를 활용한 코드 커버리지 체크가 될 수 있겠다.

---

## Jacoco (Java Code Coverage) 란?
부끄럽지만, 처음들어 봤다. ( 코코아가 먹고싶다 )
> Java 및 Kotlin에서 널리 쓰이는 **코드 커버리지 측정 도구**  

- 테스트가 실제 코드의 어느 부분을 실행했는지, 어느 라인이 비어 있는지를 알려준다.
- Gradle/Maven과 쉽게 통합 가능하며 HTML, XML, CSV 등의 리포트로 확인 할 수 있다.
- 팀/프로젝트 기준으로 수치 기반 품질 기준 설정도 가능하다.
  - 전체 LINE 커버리지 ≥ 80% 
  - 전체 BRANCH 커버리지 ≥ 70%

> 엥 ... Jacoco는 앵간하면, **코드 커버리지 측정의 표준**으로 자리 잡았다 봐도 무방할듯 싶다.
---

## 4. Jacoco + 연동 대상 비교

| 연동 대상           | 특징 및 용도                            | 커버리지 시각화         | PR 코멘트 지원 | 기타 분석 기능                      |
|--------------------|----------------------------------------|--------------------------|----------------|--------------------------------------|
| **Codecov**      | **가볍고 쉬움** <br> GitHub Actions와 찰떡 | 웹/뱃지/라인별 표시      | 자동 코멘트     |  커버리지 외 품질 분석 없음             |
| **SonarQube**    | **정밀 품질 분석용** <br> 보안/스멜/중복 체크 |  Dashboard UI 기반       | 기본 미지원     | 정적 분석, 보안 이슈, 스멜 등 포함     |

> 개인 프로젝트나 빠른 피드백이 필요한 경우엔 Codecov  
> 팀 협업, 품질 게이트/보안까지 챙기려면 SonarQube와 연동

---

## 어떤 구성으로 최고 베스트 일까 ?

멘토님 과제는 단순히 Jacoco 연동이 아니라, **실제 서비스 코드의 품질을 지키기 위한 전체 흐름을 구성해보는 것**으로 이해했다.

| 구성 요소                    | 설명                                                                       |
|-----------------------------|--------------------------------------------------------------------------|
| **Gradle 빌드**             | 기본 빌드 + 테스트 task                                                         |
| **Jacoco 적용**             | `test` task 이후 `jacocoTestReport` 를`jacocoTestCoverageVerification` 로 연결 |
| **Coverage 기준 설정**       | `LINE ≥ 80%`, `BRANCH ≥ 70%` 등 명시 후 Fail 조건 설정                           |
| **SonarQube 연동 (선택)**   | 중복/스멜 등 정적 분석까지 추가                                                       |
| **취약점 감지 (OWASP 등)**  | `dependencyCheck` 또는 `snyk` 등으로 라이브러리 보안 확인 (log4j 사태 등..)               |
| **CI 연동**                | GitHub Actions 등으로 PR 시 자동 실행, 배지 + 리포트 코멘트                              

---

## 결론 적으로

- **코드리뷰는 더 이상 인간의 눈에만 의존할 수 없다.**
- 그래서 **커버리지 도구 + 정적 분석 + 취약점 탐지**를 통해 **객관적 품질 지표**를 확보해야 한다.
- **빠르고 믿을 수 있는 품질 보증** 

> Jacoco 적용하면 PR에 커버리지 뱃지가 붙는다던데, 품질 신뢰도가 올라가는 것을 체감해 봐야겠다.

---

## jacoco Gradle 설정

```kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  ....
  id("jacoco") // 코드 커버리지 측정
}

group = "io.github.hyungkishin"
version = "0.0.1-SNAPSHOT"

/**
 * Coverage 정책
 * - LINE ≥ 80%
 * - BRANCH ≥ 70%
 */
object CoveragePolicy {
  val line: BigDecimal get() = 0.80.toBigDecimal()
  val branch: BigDecimal get() = 0.70.toBigDecimal()
}

.....

// Jacoco 기본 설정
jacoco {
  toolVersion = "0.8.11"
}

/**
 * 공통 exclude 규칙 (커버리지 테스트 대상에서 제외할 클래스 패턴)

 */
val excludedPatterns = listOf(
  "**/*ApplicationKt.class",    // main 함수 top-level 클래스 (Kotlin)
  "**/*Application.class",      // SpringBootApplication 클래스
  "**/*Dto.class",              // DTO 클래스
  "**/*DtoKt.class",            // companion object 등 포함된 Kotlin Dto 관련
  "**/*Request.class",          // 요청용 객체
  "**/*Response.class",         // 응답용 객체
  "**/*Config.class",            // 설정 클래스
  "**/*Exception.class",        // 예외 클래스
  "**/*Constants.class",        // 상수 클래스
  "**/*Interceptor.class",      // 인터셉터
  "**/*Mapper.class",           // Mapper 클래스
  "**/*Extensions.class",       // Kotlin 확장 함수 모음
  "**/Q*.class"                 // QueryDSL Q 클래스
)

val classDirsToAnalyze = fileTree("build/classes/kotlin/main").matching {
  exclude(excludedPatterns)
}

// 커버리지 기준 검증 설정 (JacocoViolation) - 커버리지 미달 시 빌드 실패
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  classDirectories.setFrom(files(classDirsToAnalyze))
  sourceDirectories.setFrom(files("src/main/kotlin"))
  executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))

  violationRules {
    rule {
      enabled = true
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        minimum = CoveragePolicy.line
      }
      limit {
        counter = "BRANCH"
        value = "COVEREDRATIO"
        minimum = CoveragePolicy.branch
      }
    }
  }
}

tasks.named<JacocoReport>("jacocoTestReport") {
  dependsOn(tasks.test)

  classDirectories.setFrom(files(classDirsToAnalyze))
  sourceDirectories.setFrom(files("src/main/kotlin"))
  executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))

  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

// testCoverage: 테스트 / 커버리지 리포트 / 검증을 포함한 태스크
tasks.register("testCoverage") {
  group = "verification"
  description = "Run tests, generate report, verify coverage"
  dependsOn("test", "jacocoTestReport", "jacocoTestCoverageVerification")
}

// check 태스크에 통합 (TODO: CI에서 사용 될 기본 태스크)
tasks.named("check") {
  dependsOn("testCoverage")
}


```

### 커버리지 리포트 보기

```bash
  
$ ./gradlew clean build

$ open build/reports/jacoco/test/html/index.html
```
초록색: 커버된 코드  
빨간색: 커버되지 않음  
노란색: 분기 조건 일부만 커버됨


## Jacoco의 동작 방식
Jacoco는 테스트 실행 시 코드 커버리지를 추적하기 위해 다음과 같은 방식으로 동작한다

| 단계        | 설명                                      |
|-----------| --------------------------------------- |
| 1. 테스트 실행 | Jacoco Agent가 `.exec`에 실행 이력 기록         |
| 2. 리포트 생성 | `.exec` + `.class`를 매칭해 리포트 생성          |
| 3. 기준 검증  | 설정한 기준(LINE 80%, BRANCH 70%) 위반 시 빌드 실패 |


1. `test` 태스크 실행 시, Jacoco Agent가 `.exec` 파일에 **"어떤 클래스의 어떤 라인이 실제 실행되었는지"** 기록한다.
2. 이후 `jacocoTestReport` 태스크를 실행하면, Jacoco는 `.exec` 파일과 빌드된 `.class` 파일을 **정확히 매칭해서 리포트**를 생성한다. 

즉, 커버리지 리포트는 **실행 결과(`.exec`) + 실행 시점의 바이트코드(`.class`)** 조합으로 생성된다.
`.exec`는 단순 실행 이력 로그가 아니라, **클래스별 커버된 명령어 단위 정보**가 들어있는 바이너리 파일이다.

> 하지만 이 과정은 **빌드 순서에 따라 쉽게 깨질 수 있다.**  
> `test` 태스크가 실행되지 않으면 `.exec` 파일이 생성되지 않아 리포트 생성 시 오류가 발생한다.  
> 
> Gradle + Jacoco에서 올바른 실행 순서는 다음과 같다.  
> test -> jacocoTestReport -> jacocoTestCoverageVerification