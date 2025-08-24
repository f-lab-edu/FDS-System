package io.github.hyungkishin.transentia.shared.error

/**
 * 여러 도메인에서 동일 의미로 재사용 가능한 도메인 공통 오류 모음.
 *
 * 공통화 기준:
 * - 도메인이 달라도 의미가 변하지 않는 범주만 채택 (ex. NotFound, InvalidArgument 등)
 * - 도메인 고유 규칙(예: 잔액 부족, 사기 의심)은 각 도메인 모듈에서 정의한다.
 */
sealed interface CommonError : DomainError {

    /**
     * 리소스를 찾지 못한 경우.
     *
     * @param resource 리소스 종류(예: "user", "account")
     * @param id 식별자(문자열로 통일)
     */
    data class NotFound(
        val resource: String,
        val id: String
    ) : CommonError {
        override val code: String = "common.not-found"
        override val message: String = "리소스를 찾을 수 없습니다."
        override val meta: Map<String, Any?> = mapOf("resource" to resource, "id" to id)
    }

    /**
     * 인자가 유효하지 않은 경우.
     *
     * @param field 필드명
     * @param reason 구체 사유(선택)
     */
    data class InvalidArgument(
        val field: String,
        val reason: String? = null
    ) : CommonError {
        override val code: String = "common.invalid-argument"
        override val message: String = "요청 값이 유효하지 않습니다."
        override val meta: Map<String, Any?> = mapOf("field" to field, "reason" to reason)
    }

    /** 권한 없음. */
    public data object PermissionDenied : CommonError {
        override val code: String = "common.permission-denied"
        override val message: String = "권한이 없습니다."
    }

    /** 리소스 상태 충돌. */
    public data class Conflict(val reason: String) : CommonError {
        override val code: String = "common.conflict"
        override val message: String = "리소스 상태 충돌이 발생했습니다."
        override val meta: Map<String, Any?> = mapOf("reason" to reason)
    }

    /**
     * 외부 의존 오류 (HTTP 502로 매핑하기 적합).
     *
     * @param service 외부 서비스 식별자
     * @param detail 추가 정보(선택)
     */
    data class ExternalDependencyError(
        val service: String,
        val detail: String? = null
    ) : CommonError {
        override val code: String = "common.external-dependency"
        override val message: String = "외부 서비스 오류가 발생했습니다."
        override val meta: Map<String, Any?> = mapOf("service" to service, "detail" to detail)
    }

    /** 과도한 요청. */
    data object RateLimited : CommonError {
        override val code: String = "common.rate-limited"
        override val message: String = "요청이 과도합니다. 잠시 후 다시 시도해 주세요."
    }

    /** 시간 초과. */
    data object Timeout : CommonError {
        override val code: String = "common.timeout"
        override val message: String = "요청이 시간 초과되었습니다."
    }
}