package io.github.hyungkishin.transentia.common.error

/**
 * 도메인/애플리케이션 계층에서 사용하는 **비즈니스 오류 모델의 공통 인터페이스**.
 *
 * - 전달(HTTP/gRPC)이나 인프라(JPA/외부 API) 개념과 무관해야 한다.
 * - [code]는 클라이언트/로깅/알람에서 식별 가능한 짧은 문자열(네임스페이스 권장).
 * - [message]는 사람 친화적 기본 메시지.
 * - [meta]는 디버깅/추적을 위한 부가 정보(필요 시만 사용).
 */
interface DomainError {
    val code: String
    val message: String
    val meta: Map<String, Any?>
        get() = emptyMap()
}