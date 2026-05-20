package io.github.hyungkishin.transentia.api.performance

import java.util.concurrent.ThreadLocalRandom
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 내부 더미 엔드포인트
 * 외부 API rate limiting 없이 순수하게 Connection Pool 효과만 테스트
 */
@RestController
@RequestMapping("/dummy")
class DummyController {
    /**
     * 빠른 응답 (10ms 지연)
     */
    @GetMapping("/fast")
    fun fast(): DummyResponse {
        Thread.sleep(10)
        return DummyResponse(
            message = "Fast response",
            timestamp = System.currentTimeMillis(),
            data = generateRandomString(100),
        )
    }

    /**
     * 중간 응답 (50ms 지연)
     */
    @GetMapping("/medium")
    fun medium(): DummyResponse {
        Thread.sleep(50)
        return DummyResponse(
            message = "Medium response",
            timestamp = System.currentTimeMillis(),
            data = generateRandomString(500),
        )
    }

    /**
     * 느린 응답 (200ms 지연)
     */
    @GetMapping("/slow")
    fun slow(): DummyResponse {
        Thread.sleep(200)
        return DummyResponse(
            message = "Slow response",
            timestamp = System.currentTimeMillis(),
            data = generateRandomString(1000),
        )
    }

    /**
     * 변동 응답 (10~100ms 랜덤 지연)
     */
    @GetMapping("/variable")
    fun variable(): DummyResponse {
        val delay = ThreadLocalRandom.current().nextLong(10, 100)
        Thread.sleep(delay)
        return DummyResponse(
            message = "Variable response (${delay}ms)",
            timestamp = System.currentTimeMillis(),
            data = generateRandomString(300),
        )
    }

    private fun generateRandomString(length: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
}

data class DummyResponse(
    val message: String,
    val timestamp: Long,
    val data: String,
)
