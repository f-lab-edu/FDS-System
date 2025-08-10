package io.github.hyungkishin.transentia.shared.exception.message

import org.springframework.http.HttpStatus

/**
 * 공통 에러코드
 */
enum class CommonErrorCode(
    override val code: String,
    override val message: String,
    override val httpStatus: HttpStatus = HttpStatus.BAD_REQUEST
) : ErrorCode {

    SERVER_ERROR("server-error", "서버 오류", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_REQUEST("invalid-request", "잘못된 요청"),
    INVALID_REQUEST_PARAM("invalid-parameter", "요청 파라미터 오류"),
    API_CALL_ERROR("api-error", "api 호출 오류", HttpStatus.BAD_GATEWAY),
    UNHANDLED_ERROR("error", "서버 오류. 확인 요청 필요", HttpStatus.INTERNAL_SERVER_ERROR);
}