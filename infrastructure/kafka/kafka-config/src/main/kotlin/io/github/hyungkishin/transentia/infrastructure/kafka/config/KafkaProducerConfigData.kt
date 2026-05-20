package io.github.hyungkishin.transentia.infrastructure.kafka.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "kafka-producer-config")
data class KafkaProducerConfigData(
    @NotBlank
    var keySerializer: String = "",
    @NotBlank
    var valueSerializer: String = "",
    @NotBlank
    var compressionType: String = "",
    @NotBlank
    var acks: String = "",
    /** 핵심 제약만 유지 */
    @NotNull
    var batchSize: Int = 16384, // Kafka 기본값과 동일
    @NotNull
    var batchSizeBoostFactor: Int = 1,
    @NotNull
    var lingerMs: Int = 5, // 메시지 모아서 전송 대기 시간
    @NotNull
    var requestTimeoutMs: Int = 30000,
    @NotNull
    var retryCount: Int = 5,
)
