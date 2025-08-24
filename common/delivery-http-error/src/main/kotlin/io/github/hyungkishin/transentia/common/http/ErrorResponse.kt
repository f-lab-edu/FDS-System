package io.github.hyungkishin.transentia.common.http

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val code: String,
    val message: String,
    val detail: String? = null,
    val additionalInfo: Map<String, Any?>? = null
)