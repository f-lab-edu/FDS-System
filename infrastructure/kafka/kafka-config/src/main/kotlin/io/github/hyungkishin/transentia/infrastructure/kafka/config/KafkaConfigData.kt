package io.github.hyungkishin.transentia.infrastructure.kafka.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "kafka-config")
class KafkaConfigData {
    @NotBlank
    lateinit var bootstrapServers: String

    @NotBlank
    lateinit var schemaRegistryUrlKey: String

    @NotBlank
    lateinit var schemaRegistryUrl: String

    // 필요 시 운영에서 확장 가능
    @NotNull
    var numOfPartitions: Int = 3

    @NotNull
    var replicationFactor: Short = 3
}
