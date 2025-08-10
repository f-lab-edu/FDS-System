package io.github.hyungkishin.transentia.shared.http.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiCommonResponse<T>(
    val code: String = "ok",
    val message: String = "success",
    val data: T? = null,
    val traceId: String? = null,
)