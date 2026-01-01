package io.github.hyungkishin.transentia.api.performance

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate

/**
 * RestTemplate 성능 테스트를 위한 컨트롤러
 * 
 * /test/basic   - 기본 RestTemplate 사용 (Connection Pool 없음)
 * /test/pooled  - Connection Pool이 설정된 RestTemplate 사용
 */
@RestController
@RequestMapping("/test")
class PerformanceTestController(
    @Qualifier("basicRestTemplate") private val basicRestTemplate: RestTemplate,
    @Qualifier("pooledRestTemplate") private val pooledRestTemplate: RestTemplate
) {

    companion object {
        private const val EXTERNAL_API_URL = "https://jsonplaceholder.typicode.com/posts/1"
    }

    /**
     * 기본 RestTemplate
     * - 매 요청마다 새로운 TCP 연결 생성
     * - 요청 완료 후 연결 종료
     * - TPS가 높아지면 TIME_WAIT 소켓이 대량 발생
     */
    @GetMapping("/basic")
    fun testBasic(): String {
        return basicRestTemplate.getForObject(EXTERNAL_API_URL, String::class.java)
            ?: "No response"
    }

    /**
     * Connection Pool RestTemplate
     * - TCP 연결을 Pool에서 재사용
     * - 연결 생성/종료 오버헤드 최소화
     * - TIME_WAIT 소켓 발생 최소화
     */
    @GetMapping("/pooled")
    fun testPooled(): String {
        return pooledRestTemplate.getForObject(EXTERNAL_API_URL, String::class.java)
            ?: "No response"
    }

}
