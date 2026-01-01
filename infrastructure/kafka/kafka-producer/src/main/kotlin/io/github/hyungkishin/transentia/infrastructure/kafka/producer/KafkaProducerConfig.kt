package io.github.hyungkishin.transentia.infrastructure.kafka.producer

import io.github.hyungkishin.transentia.infrastructure.kafka.config.KafkaConfigData
import io.github.hyungkishin.transentia.infrastructure.kafka.config.KafkaProducerConfigData
import org.apache.avro.specific.SpecificRecordBase
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import java.io.Serializable

@Configuration
class KafkaProducerConfig<K : Serializable, V : SpecificRecordBase>(
    private val kafkaConfigData: KafkaConfigData,
    private val kafkaProducerConfigData: KafkaProducerConfigData
) {

    @Bean
    fun producerConfig(): Map<String, Any> {
        return mutableMapOf<String, Any>().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfigData.bootstrapServers)
            put(kafkaConfigData.schemaRegistryUrlKey, kafkaConfigData.schemaRegistryUrl)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, kafkaProducerConfigData.keySerializer)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, kafkaProducerConfigData.valueSerializer)
            put(ProducerConfig.BATCH_SIZE_CONFIG,
                kafkaProducerConfigData.batchSize * kafkaProducerConfigData.batchSizeBoostFactor)
            put(ProducerConfig.LINGER_MS_CONFIG, kafkaProducerConfigData.lingerMs)
            put(ProducerConfig.COMPRESSION_TYPE_CONFIG, kafkaProducerConfigData.compressionType)
            put(ProducerConfig.ACKS_CONFIG, kafkaProducerConfigData.acks)
            put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, kafkaProducerConfigData.requestTimeoutMs)
            put(ProducerConfig.RETRIES_CONFIG, kafkaProducerConfigData.retryCount)
        }
    }

    @Bean
    fun producerFactory(): ProducerFactory<K, V> {
        return DefaultKafkaProducerFactory(producerConfig())
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<K, V> {
        return KafkaTemplate(producerFactory()).apply {
            setObservationEnabled(true)
        }
    }
}
