package io.github.hyungkishin.transentia.infrastructure.kafka.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "kafka-consumer-config")
class KafkaConsumerConfigData {
    @NotBlank
    lateinit var keyDeserializer: String

    @NotBlank
    lateinit var valueDeserializer: String

    @NotBlank
    lateinit var consumerGroupId: String

    @NotBlank
    lateinit var autoOffsetReset: String

    @NotBlank
    lateinit var specificAvroReaderKey: String

    @NotBlank
    lateinit var specificAvroReader: String

    @NotNull
    var batchListener: Boolean = true

    @NotNull
    var autoStartup: Boolean = true

    @NotNull
    var concurrencyLevel: Int = 3 // 반드시 1 이상이어야 하므로 기본값 3

    @NotNull
    var sessionTimeoutMs: Int = 10000

    @NotNull
    var heartbeatIntervalMs: Int = 3000

    @NotNull
    var maxPollIntervalMs: Int = 300000

    @NotNull
    var pollTimeoutMs: Long = 150

    @NotNull
    var maxPollRecords: Int = 500

    @NotNull
    var maxPartitionFetchBytesDefault: Int = 1048576

    @NotNull
    var maxPartitionFetchBytesBoostFactor: Int = 1
}
