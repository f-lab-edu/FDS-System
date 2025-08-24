package io.github.hyungkishin.transentia.common.error

/**
 * 도메인/애플리케이션 계층에서 발생하는 **비즈니스 예외**.
 *
 * - 내부에는 [DomainError]만 담는다.
 * - 전달/인프라 레이어에서 이 예외를 받아 **채널별 오류 형식(HTTP 상태/에러 바디)**로 변환한다.
 */
open class DomainException(
    val error: DomainError,
    val detail: String? = null,
    cause: Throwable? = null
) : RuntimeException(error.message, cause)
