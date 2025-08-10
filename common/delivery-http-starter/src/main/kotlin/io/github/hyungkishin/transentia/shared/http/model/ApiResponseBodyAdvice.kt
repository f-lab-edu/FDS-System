// delivery-http-starter/src/main/kotlin/.../ApiResponseBodyAdvice.kt
package io.github.hyungkishin.transentia.shared.http

import io.github.hyungkishin.transentia.shared.http.model.ApiCommonResponse
import org.springframework.core.MethodParameter
import org.springframework.core.io.Resource
import org.springframework.http.HttpEntity
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestControllerAdvice
@Component
class ApiResponseBodyAdvice : ResponseBodyAdvice<Any> {

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>
    ): Boolean {
        // @NoWrap 붙은 곳은 제외
        val hasNoWrap = returnType.containingClass.isAnnotationPresent(NoWrap::class.java) ||
                returnType.hasMethodAnnotation(NoWrap::class.java)

        if (hasNoWrap) return false

        // 이미 래핑되었거나 감싸면 안 되는 타입들 제외
        val clazz = returnType.parameterType
        return when {
            ResponseEntity::class.java.isAssignableFrom(clazz) -> false
            HttpEntity::class.java.isAssignableFrom(clazz)     -> false
            Resource::class.java.isAssignableFrom(clazz)       -> false
            StreamingResponseBody::class.java.isAssignableFrom(clazz) -> false
            ErrorResponse::class.java.isAssignableFrom(clazz)  -> false
            clazz == String::class.java                        -> false // 문자열은 MessageConverter 이슈
            clazz == Void.TYPE                                 -> false
            else -> true
        }
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: org.springframework.http.server.ServerHttpRequest,
        response: org.springframework.http.server.ServerHttpResponse
    ): Any? {
        // 에러/이미 래핑된 경우는 그대로
        if (body == null) return ApiCommonResponse<Unit>(data = null)
        if (body is ApiCommonResponse<*>) return body
        if (body is ErrorResponse) return body

        // 파일/스트림 류는 건드리지 않음
        if (body is Resource || body is StreamingResponseBody || body is HttpEntity<*>) return body

        // 성공 래핑
        return ApiCommonResponse(data = body)
    }
}
