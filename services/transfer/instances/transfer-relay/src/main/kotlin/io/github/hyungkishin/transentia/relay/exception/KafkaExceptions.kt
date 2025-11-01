package io.github.hyungkishin.transentia.relay.exception

/**
 * 재시도 가능한 Kafka 예외
 * 
 * 일시적 네트워크 오류, Broker 일시 장애 등
 */
class RetryableKafkaException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 재시도 불가능한 Kafka 예외
 * 
 * Serialization 실패, 잘못된 토픽 등
 */
class NonRetryableKafkaException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 잘못된 데이터 예외
 * 
 * Payload 파싱 실패, 필수 필드 누락 등
 */
class InvalidEventDataException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
