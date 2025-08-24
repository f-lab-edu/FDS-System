package io.github.hyungkishin.transentia.common

import io.github.hyungkishin.transentia.common.exception.message.ErrorCode

class ApiRuntimeException(
    val errorCode: ErrorCode,
    val detailMessage: String? = null,
    val additionalInfo: Any? = null
) : RuntimeException(errorCode.message) {
}