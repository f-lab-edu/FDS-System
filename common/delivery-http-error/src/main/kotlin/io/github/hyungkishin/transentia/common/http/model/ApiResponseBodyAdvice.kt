package io.github.hyungkishin.transentia.common.http.model

import io.github.hyungkishin.transentia.common.http.ErrorResponse
import io.github.hyungkishin.transentia.common.http.NoWrap
import org.springframework.core.MethodParameter
import org.springframework.core.io.Resource
import org.springframework.http.*
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestControllerAdvice
@Component
class ApiResponseBodyAdvice(
    private val customizers: List<ApiResponseCustomizer> = emptyList()
) : ResponseBodyAdvice<Any> {

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>
    ): Boolean {
        val noWrap =
            returnType.containingClass.isAnnotationPresent(NoWrap::class.java) ||
                    returnType.hasMethodAnnotation(NoWrap::class.java)
        if (noWrap) return false

        val clazz = returnType.parameterType
        return when {
            Resource::class.java.isAssignableFrom(clazz) -> false
            StreamingResponseBody::class.java.isAssignableFrom(clazz) -> false
            clazz == String::class.java -> false
            clazz == Void.TYPE -> false
            else -> true // ★ ResponseEntity 포함
        }
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): Any? {
        val req  = request as? ServletServerHttpRequest
        val resp = response as? ServletServerHttpResponse

        // 도메인별 커스터마이징 실행 (상태/헤더 자동 설정 등)
        if (req != null && resp != null) {
            customizers.filter { it.supports(body) }.forEach { it.customize(body, req, resp) }
        }

        // 파일/스트림/에러/이미 래핑된 건 그대로
        if (body == null) return ApiCommonResponse<Unit>(data = null)
        if (body is ErrorResponse || body is Resource || body is StreamingResponseBody) return body
        if (body is ApiCommonResponse<*>) return body

        // ★ ResponseEntity → 바디만 래핑, 헤더/상태 보존
        if (body is ResponseEntity<*>) {
            val inner = body.body ?: return body
            if (inner is ErrorResponse || inner is ApiCommonResponse<*>) return body
            val wrapped = ApiCommonResponse(data = inner)
            return ResponseEntity.status(body.statusCode).headers(body.headers).body(wrapped)
        }

        // 일반 DTO → 래핑
        return ApiCommonResponse(data = body)
    }
}
