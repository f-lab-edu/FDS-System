# 분산 시스템에서 네트워크 리소스 효율적으로 관리하기 1편

## 문제의 시작

MSA 환경에서 Kafka를 이용한 이벤트 발행/소비를 구현하던 중, 성능 테스트를 진행하게 됐다. localhost에서 테스트하다 보니 실제 환경과는 다른 결과가 나왔고, 멘토링 시간에 피드백을 받았다.

> "TCP Keep-Alive / Idle Timeout / TIME_WAIT / CLOSE_WAIT 등에 대한 TPS의 영향도를 파악할 것"  
> "클라이언트 타임아웃(connect/read/풀대기) <-> 서버 keep-alive/idle timeout 등의 상호 정합성도 같이 봐주세요"

TCP/IP 책을 읽거나 소켓 프로그래밍을 공부하는 것도 방법이겠지만, 시간이 오래 걸릴 것 같았다. 그래서 양질의 블로그를 가볍게 읽고, **문제부터 직접 만나보자**고 생각했다.

## RestTemplate과 Connection Pool

MSA 기반으로 서버간 통신할 때 RestTemplate을 많이 쓴다. 근데 설정을 제대로 본 적이 없었다.

```kotlin
@Bean
fun restTemplate(): RestTemplate {
    return RestTemplate()
}
```

싱글톤으로 관리되고 객체도 재사용되니까 괜찮은 줄 알았다. Connection Pool이 정말 필요할까?

두 가지 버전을 만들어서 비교해봤다.

### 1안 - 기본 RestTemplate

```kotlin
@Bean("basicRestTemplate")
fun basicRestTemplate(): RestTemplate {
    return RestTemplate()
}
```

내부적으로 `SimpleClientHttpRequestFactory`를 사용한다. 매 요청마다 새로운 TCP 소켓을 생성하고 종료한다.

### 2안 - Connection Pool 설정

Apache HttpClient 문서를 참고해서 설정했다.

```kotlin
@Bean("pooledRestTemplate")
fun pooledRestTemplate(): RestTemplate {
    val connectionConfig = ConnectionConfig.custom()
        .setConnectTimeout(Timeout.ofSeconds(3))
        .setSocketTimeout(Timeout.ofSeconds(30))
        .setTimeToLive(TimeValue.ofMinutes(5))
        .setValidateAfterInactivity(TimeValue.ofSeconds(10))
        .build()

    val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
        .setMaxConnTotal(200)
        .setMaxConnPerRoute(100)
        .setDefaultConnectionConfig(connectionConfig)
        .build()

    val requestConfig = RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofSeconds(10))
        .setResponseTimeout(Timeout.ofSeconds(30))
        .build()

    val httpClient = HttpClientBuilder.create()
        .setConnectionManager(connectionManager)
        .setDefaultRequestConfig(requestConfig)
        .setKeepAliveStrategy { _, _ -> TimeValue.ofSeconds(30) }
        .evictIdleConnections(TimeValue.ofSeconds(60))
        .evictExpiredConnections()
        .build()

    return RestTemplate(HttpComponentsClientHttpRequestFactory(httpClient))
}
```

Apache HttpClient 5의 `PoolingHttpClientConnectionManager`를 사용해 TCP 연결을 재사용한다.

### 테스트용 엔드포인트

```kotlin
@RestController
class PerformanceTestController(
    @Qualifier("basicRestTemplate") private val basicRestTemplate: RestTemplate,
    @Qualifier("pooledRestTemplate") private val pooledRestTemplate: RestTemplate
) {
    @GetMapping("/test/basic")
    fun testBasic(): String {
        return basicRestTemplate.getForObject(
            "https://jsonplaceholder.typicode.com/posts/1",
            String::class.java
        ) ?: "No response"
    }

    @GetMapping("/test/pooled")
    fun testPooled(): String {
        return pooledRestTemplate.getForObject(
            "https://jsonplaceholder.typicode.com/posts/1",
            String::class.java
        ) ?: "No response"
    }
}
```

## 외부 API 를 기반으로, 확인 - Apache Bench 

외부 API(`jsonplaceholder.typicode.com`)를 이용하여 테스트를 진행했다.

```bash
# 기본 RestTemplate
ab -n 10000 -c 100 -t 30 http://localhost:8080/test/basic

# 60초 대기 (TIME_WAIT 정리)
sleep 60

# Connection Pool
ab -n 10000 -c 100 -t 30 http://localhost:8080/test/pooled
```

별도 터미널에서 소켓 상태를 실시간으로 모니터링했다

```bash
watch -n 1 "netstat -an | grep TIME_WAIT | wc -l"
```

**결과**

| 항목 | 기본 RestTemplate | Connection Pool | 차이 |
|------|------------------|-----------------|------|
| TPS | 504 | 652 | +30% |
| 평균 응답시간 | 198ms | 153ms | -23% |
| 총 요청 수 | 15,120 | 19,576 | +30% |

확실히 Connection Pool이 더 빠르다.

## TIME_WAIT 소켓 폭발

TPS 차이는 예상했는데, 모니터링 했던 터미널을 보는 순간 혼란스러웠다.

**기본 RestTemplate 실행 중**
```
시작: 221개
실행 중: 16,141개 (최대 피크)
테스트 종료: 16,275개
```

**Connection Pool 실행 중**
```
21시 59분: 1~2개
23시 40분: 3~4개
23시 41분: 3~4개
→ 19,576개 요청을 보냈는데도...?
```

기본 RestTemplate 다시 실행했을 때 16,133개 폭발 해버린 것이다.

| 항목 | 기본 | Pool |
|------|------|------|
| TIME_WAIT 소켓 | 16,275개 | 4개 |
| 차이 | - | -99.97% |

이게 진짜 문제였다.

### 기본 RestTemplate의 동작

요청 1개당 아래와 같은 과정을 거친다

```
1. TCP 연결 생성 (3-way handshake)
   SYN → SYN-ACK → ACK

2. HTTP 요청/응답

3. TCP 연결 종료 (4-way handshake)
   FIN → ACK → FIN → ACK

4. TIME_WAIT 상태 진입
   Mac: 15초 유지
   Linux: 60초 유지
```

15,120개 요청이 오면
1. 15,120개 연결 생성
2. 15,120개 연결 종료
3. 16,275개 TIME_WAIT 소켓 발생

이제서야 Connection Pool을 만날 수 있게 된 것 같다.

### Connection Pool의 동작

```
1. 100개 연결을 미리 생성하고 유지
2. 요청이 오면 Pool에서 꺼내서 재사용
3. 요청 완료 후 Pool에 반환 (종료하지 않음)
```

19,576개 요청이 와도
- 100개 연결로 모두 처리
- 연결을 종료하지 않아서 TIME_WAIT 발생 안 함
- TIME_WAIT: 4개 (초기 생성 시 일부만)

watch -n 1 "netstat -an | grep 'jsonplaceholder' | grep ESTABLISHED | wc -l"

## 뭐가 문제인지?

### 포트 고갈

운영체제가 사용할 수 있는 포트는 제한되어 있다

```
Mac/Linux ephemeral port: 약 28,000~60,000개
TIME_WAIT 16,275개 = 포트의 25~50% 점유
```

TPS가 1000이라면?
```
1초에 1000개 연결 생성/종료
60초 × 1000 TPS = 60,000개 TIME_WAIT
→ 포트 고갈
→ "Cannot assign requested address" 에러
→ 서비스 장애
```

### 메모리와 CPU

- 16,275개 소켓 = 수십 MB 메모리
- 소켓 상태 추적, 타이머 관리 등 CPU 비용

## Apache Bench의 한계

숫자는 나왔지만 패턴이 궁금했다. TPS가 어떻게 변하는지, 언제 에러가 발생하는지 보고 싶었다. 그래서 nGrinder를 사용했다.

### nGrinder 설정

```
에이전트: 1
Vuser: 100
테스트 시간: 30초
```

### 시각화된 결과

**기본 RestTemplate:**

![img.png](../image/커넥션%20풀이%20설정되지%20않은%20RestTemplate.png)

```
TPS: 65
에러: 32개
패턴: 초반 90 TPS → 후반 30 TPS (급락)
```

**Connection Pool:**

![img_1.png](../image/커넥션풀이%20설정된%20RestTemplate.png)

```
TPS: 527.7 (8배 차이)
에러: 0개
패턴: 400 TPS → 600 TPS (안정적)
```

| 항목 | 기본 | Pool | 차이 |
|------|------|------|------|
| TPS | 65.0 | 527.7 | +712% |
| 평균 응답시간 | 1,392ms | 203ms | -85% |
| 에러 | 32개 | 0개 | 완벽 |
| TIME_WAIT | 7,919개 | 3~4개 | -99.95% |

그래프를 보니 차이가 명확했다. 기본 RestTemplate은 외부 API의 rate limiting에 걸려서 후반에 급락했다. Connection Pool은 연결을 재사용해서 안정적으로 처리했다.

## 왜 외부 API에서 더 차이가 클까?

**TCP handshake 비용:**
- localhost: 마이크로초 단위
- 외부 서버: 수십~수백 밀리초

**네트워크 지연:**
- localhost: 거의 없음
- 외부 서버: RTT(Round Trip Time) 존재

localhost에서도 30% 향상이 있었는데, 외부 서버에서는 8배 차이가 났다.

## 결론

Connection Pool은 선택이 아니라 필수다.

**측정 결과:**
- TPS 8배 향상
- 응답시간 85% 단축
- 에러 0개
- TIME_WAIT 소켓 99.95% 감소

처음에 "싱글톤이면 충분하지 않을까?"라고 생각했다. 직접 테스트해보니 답이 나왔다.

## 재현 방법

### 1. 프로젝트 구조

```
src/test/kotlin/performance/
├── RestTemplateConfig.kt
├── PerformanceTestController.kt
└── PerformanceTestApplication.kt
```

### 2. Apache Bench 테스트

```bash
# 서버 실행
./gradlew bootRun

# 소켓 모니터링
watch -n 1 "netstat -an | grep TIME_WAIT | wc -l"

# 부하 테스트
ab -n 10000 -c 100 -t 30 http://localhost:8080/test/basic
sleep 60
ab -n 10000 -c 100 -t 30 http://localhost:8080/test/pooled
```

### 3. nGrinder 테스트

```
1. Docker로 nGrinder 실행
2. 스크립트 작성 (Groovy)
3. 테스트 실행 (Vuser 100, 30초)
4. 그래프 확인
```

## 참고 자료

- [brewagebear - 커널과 함께 알아보는 소켓과 TCP Deep Dive](https://brewagebear.github.io/linux-kernel-internal-3/)
- [Kakao Tech - CLOSE_WAIT & TIME_WAIT 최종 분석](https://tech.kakao.com/posts/321)
- [Apache HttpClient 5 Documentation](https://hc.apache.org/httpcomponents-client-5.2.x/)

---

*테스트 환경: Spring Boot 3.3.2, Kotlin 1.9.25, Apache HttpClient 5.2.1, Mac OS*


그라파나 대시보드 연결 + 엑츄에이터



현재 상태에서,



cloude 환경에 띄워보기.





서버환경에서 어떤 환경 구성인건지 확인 호출하는쪽 부담이 ?



하나의 os 에서 소켓을 여러개 호출하는것은 한계가 있을것이다. 



