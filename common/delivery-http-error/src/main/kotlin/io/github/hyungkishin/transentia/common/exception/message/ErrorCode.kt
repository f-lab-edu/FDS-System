package io.github.hyungkishin.transentia.common.exception.message

import org.springframework.http.HttpStatus

/**
 * Exception 핸들링을 위한 기본 에러 메시지
 */
interface ErrorCode {
    val code: String
    val message: String
    val httpStatus: HttpStatus
}