package io.github.hyungkishin.transentia.common.http.model

import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse

interface ApiResponseCustomizer {
    fun supports(body: Any?): Boolean
    fun customize(
        body: Any?,
        request: ServletServerHttpRequest,
        response: ServletServerHttpResponse
    )
}