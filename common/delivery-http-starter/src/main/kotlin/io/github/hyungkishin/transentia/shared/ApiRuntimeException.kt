package io.github.hyungkishin.transentia.shared

import io.github.hyungkishin.transentia.shared.exception.message.ErrorCode

class ApiRuntimeException(
    val errorCode: ErrorCode,
    val detailMessage: String? = null,
    val additionalInfo: Any? = null
) : RuntimeException(errorCode.message) {
}