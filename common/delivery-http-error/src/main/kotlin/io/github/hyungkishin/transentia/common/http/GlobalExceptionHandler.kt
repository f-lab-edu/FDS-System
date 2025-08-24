package io.github.hyungkishin.transentia.common.http

import io.github.hyungkishin.transentia.common.error.CommonError
import io.github.hyungkishin.transentia.common.error.DomainException
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.util.StringUtils
import org.springframework.validation.BindException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestValueException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClientException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // 도메인 예외 -> HTTP
    @ExceptionHandler(DomainException::class)
    fun handleDomain(e: DomainException): ResponseEntity<ErrorResponse> {
        val status = when (e.error) {
            is CommonError.NotFound -> HttpStatus.NOT_FOUND            // 404
            is CommonError.InvalidArgument -> HttpStatus.BAD_REQUEST          // 400
            is CommonError.Conflict -> HttpStatus.CONFLICT             // 409
            is CommonError.PermissionDenied -> HttpStatus.FORBIDDEN            // 403
            is CommonError.RateLimited -> HttpStatus.TOO_MANY_REQUESTS    // 429
            is CommonError.Timeout -> HttpStatus.GATEWAY_TIMEOUT      // 504
            is CommonError.ExternalDependencyError -> HttpStatus.BAD_GATEWAY        // 502
            else -> HttpStatus.INTERNAL_SERVER_ERROR // 500 기본값
        }

        // (선택) 4xx는 warn, 5xx는 error
        if (status.is5xxServerError) {
            logger.error("DomainException: code={}, message={}, detail={}", e.error.code, e.error.message, e.detail, e)
        } else {
            logger.warn("DomainException: code={}, message={}, detail={}", e.error.code, e.error.message, e.detail)
        }

        return ResponseEntity.status(status).body(
            ErrorResponse(
                code = e.error.code,
                message = e.error.message,
                // detail이 있으면 내려주고, 없으면 meta만
                detail = e.detail,
                additionalInfo = e.error.meta
            )
        )
    }

    /**
     * 스프링/웹 표준 예외
     */

    @ExceptionHandler(ConstraintViolationException::class)
    fun handle(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val detail = e.constraintViolations.map(::violationMessage)
        return badRequest("invalid_request_param", "유효하지 않은 요청 파라미터입니다.", detail)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handle(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val detail = e.bindingResult.fieldErrors.map {
            val v = it.unwrap(ConstraintViolation::class.java)
            violationMessage(v)
        }
        return badRequest("invalid_request_body", "요청 본문 검증에 실패했습니다.", detail)
    }

    @ExceptionHandler(BindException::class)
    fun handle(e: BindException): ResponseEntity<ErrorResponse> {
        val detail = e.fieldErrors.map { fe ->
            if (fe.contains(ConstraintViolation::class.java)) {
                violationMessage(fe.unwrap(ConstraintViolation::class.java))
            } else fe.defaultMessage ?: "[${fe.field}] invalid value"
        }
        return badRequest("invalid_request_param", "요청 파라미터 바인딩에 실패했습니다.", detail)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handle(e: HttpMessageNotReadableException) =
        badRequest("invalid_request_body", "요청 본문을 읽을 수 없습니다.", e.message)

    @ExceptionHandler
    fun handle(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> =
        badRequest("missing_request_param", "[${e.parameterName}] 필수값입니다.", null)

    @ExceptionHandler(TypeMismatchException::class)
    fun handle(e: TypeMismatchException) =
        badRequest("type_mismatch", "요청 파라미터 타입이 올바르지 않습니다.", e.message)

    @ExceptionHandler(MissingRequestValueException::class)
    fun handle(e: MissingRequestValueException) =
        badRequest("missing_request_value", e.message ?: "필수 요청 값이 누락되었습니다.", null)

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handle(e: HttpMediaTypeNotSupportedException) =
        response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "지원하지 않는 Content-Type 입니다.", e.message)

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handle(e: HttpMediaTypeNotAcceptableException) =
        response(HttpStatus.NOT_ACCEPTABLE, "not_acceptable", "응답 미디어 타입을 협상하지 못했습니다.", e.message)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handle(e: MethodArgumentTypeMismatchException) =
        badRequest("type_mismatch", "파라미터 '${e.name}' 타입이 올바르지 않습니다.", e.message)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handle(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> {
        val msg = "지원하지 않는 요청 방식(${e.method}). 지원 가능 ${e.supportedHttpMethods?.joinToString().orEmpty()}"
        return response(HttpStatus.METHOD_NOT_ALLOWED, "invalid_request", msg, null)
    }

    @ExceptionHandler(RestClientException::class)
    fun handle(e: RestClientException) =
        response(HttpStatus.BAD_GATEWAY, "api_call_error", "외부 API 호출 실패", e.message)

    // 3) 그 외 모든 예외
    @ExceptionHandler(NullPointerException::class)
    fun handle(e: NullPointerException) =
        response(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", safeMessage(e), null)

    @ExceptionHandler(Exception::class)
    fun handle(e: Exception) =
        response(HttpStatus.INTERNAL_SERVER_ERROR, "unhandled_error", safeMessage(e), null)

    /**
     * helper methods
     */

    private fun badRequest(code: String, msg: String, detail: Any?) =
        response(HttpStatus.BAD_REQUEST, code, msg, detail)

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        detail: Any?
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(
                code = code,
                message = message,
                detail = when (detail) {
                    null -> null
                    is String -> detail
                    else -> detail.toString()
                }
            )
        )

    private fun violationMessage(v: ConstraintViolation<*>): String {
        val field = v.propertyPath?.toString().orEmpty().substringAfterLast('.', v.propertyPath.toString())
        return "[$field] ${v.message}"
    }

    private fun safeMessage(e: Throwable): String =
        if (StringUtils.hasText(e.message)) e.message!! else e.javaClass.simpleName
}
