package io.github.hyungkishin.transentia.api.performance

import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestTemplate

@TestConfiguration
class RestTemplateConfig {

    /**
     * 기본 RestTemplate
     * - SimpleClientHttpRequestFactory 사용
     * - 매 요청마다 새로운 TCP 연결 생성/종료
     * - Connection Pool 없음
     */
    @Bean("basicRestTemplate")
    fun basicRestTemplate(): RestTemplate {
        return RestTemplate()
    }

    /**
     * Connection Pool이 설정된 RestTemplate
     * - TCP 연결을 Pool에서 재사용
     * - 모든 타임아웃과 정리 정책 설정
     */
    @Bean("pooledRestTemplate")
    fun pooledRestTemplate(): RestTemplate {
        // ConnectionConfig: 연결 레벨 설정
        val connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(3)) // TCP 연결
            .setSocketTimeout(Timeout.ofSeconds(30))              // Socket read
            .setTimeToLive(TimeValue.ofMinutes(5))                // 최대 5분 후 재생성
            .setValidateAfterInactivity(TimeValue.ofSeconds(10))  // 10초 유휴 후 검증
            .build()

        // Connection Pool Manager
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(200)
            .setMaxConnPerRoute(100)
            .setDefaultConnectionConfig(connectionConfig)
            .build()

        // RequestConfig - 요청 레벨 설정
        val requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(10)) // Pool 대기
            .setResponseTimeout(Timeout.ofSeconds(30)) // 응답 대기
            .build()

        // HttpClient
        val httpClient = HttpClientBuilder.create()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)

            // Keep-Alive
            .setKeepAliveStrategy { _, _ ->
                TimeValue.ofSeconds(30) // 30초
            }

            // 유휴/만료 연결 정리
            .evictIdleConnections(TimeValue.ofSeconds(60)) // 60초
            .evictExpiredConnections()

            .build()

        return RestTemplate(HttpComponentsClientHttpRequestFactory(httpClient))
    }
}