package io.github.hyungkishin.transentia.common.http.tracing

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class TraceResponseHeaderFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        filterChain.doFilter(request, response)

        // Micrometer Tracing의 MDC에서 traceId 가져오기
        val traceId = MDC.get("traceId")
        if (!traceId.isNullOrBlank()) {
            response.setHeader("X-Trace-Id", traceId)
        }
    }
}
