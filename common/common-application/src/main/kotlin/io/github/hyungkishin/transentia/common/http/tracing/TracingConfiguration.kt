package io.github.hyungkishin.transentia.common.http.tracing

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.ServerRequestObservationContext

@Configuration
@ConditionalOnProperty(name = ["management.tracing.enabled"], havingValue = "true", matchIfMissing = true)
class TracingConfiguration {
    /**
     * 커스텀 헤더 전파를 위한 필터
     */
    @Bean
    fun traceResponseHeaderFilter(): TraceResponseHeaderFilter = TraceResponseHeaderFilter()

    /**
     * 추가 Span 태그 설정 (선택사항)
     */
    @Bean
    fun customSpanProcessor(): ObservationHandler<ServerRequestObservationContext> =
        object : ObservationHandler<ServerRequestObservationContext> {
            override fun onStart(context: ServerRequestObservationContext) {
                context.addHighCardinalityKeyValue(
                    KeyValue.of("http.user_agent", context.carrier.getHeader("User-Agent") ?: "unknown"),
                )
            }

            override fun supportsContext(context: Observation.Context): Boolean = context is ServerRequestObservationContext
        }
}
