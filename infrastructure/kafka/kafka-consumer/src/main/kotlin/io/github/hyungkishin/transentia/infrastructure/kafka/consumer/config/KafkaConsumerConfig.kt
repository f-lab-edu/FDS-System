package io.github.hyungkishin.transentia.infrastructure.kafka.consumer.config

import io.github.hyungkishin.transentia.infrastructure.kafka.config.KafkaConfigData
import io.github.hyungkishin.transentia.infrastructure.kafka.config.KafkaConsumerConfigData
import org.apache.avro.specific.SpecificRecordBase
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.KafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import java.io.Serializable

@Configuration
class KafkaConsumerConfig<K : Serializable, V : SpecificRecordBase>(
    private val kafkaConfigData: KafkaConfigData,
    private val kafkaConsumerConfigData: KafkaConsumerConfigData
) {

    @Bean
    fun consumerConfigs(): Map<String, Any> {
        return mutableMapOf<String, Any>().apply {
            // 기본 설정
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfigData.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, kafkaConsumerConfigData.consumerGroupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, kafkaConsumerConfigData.keyDeserializer)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, kafkaConsumerConfigData.valueDeserializer)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaConsumerConfigData.autoOffsetReset)
            
            // Avro 설정
            put(kafkaConfigData.schemaRegistryUrlKey, kafkaConfigData.schemaRegistryUrl)
            put(kafkaConsumerConfigData.specificAvroReaderKey, kafkaConsumerConfigData.specificAvroReader)
            
            // Consumer Group 관리
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, kafkaConsumerConfigData.sessionTimeoutMs)
            put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, kafkaConsumerConfigData.heartbeatIntervalMs)
            put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, kafkaConsumerConfigData.maxPollIntervalMs)
            
            // Fetch 설정
            put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
                kafkaConsumerConfigData.maxPartitionFetchBytesDefault * 
                kafkaConsumerConfigData.maxPartitionFetchBytesBoostFactor
            )
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafkaConsumerConfigData.maxPollRecords)
            
            // Fetch 최소 바이트: 1KB
            // - 브로커가 최소 이 크기만큼 데이터가 쌓일 때까지 대기
            // - 너무 작으면 네트워크 오버헤드, 너무 크면 지연 발생
            put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024)
            
            // Fetch 최대 대기 시간: 500ms
            // - fetch.min.bytes에 도달하지 않아도 이 시간 후 응답
            // - 실시간성과 처리량의 균형
            put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500)
            
            // 자동 커밋 비활성화 (수동 제어)
            // - Spring Kafka의 AckMode로 제어
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            
            // Isolation Level: read_committed
            // - 트랜잭션 커밋된 메시지만 읽음
            // - 데이터 정합성 보장
            put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
            
            // Client ID (모니터링용)
            put(ConsumerConfig.CLIENT_ID_CONFIG, "fds-consumer-\${spring.application.name}")
        }
    }

    @Bean
    fun consumerFactory(): ConsumerFactory<K, V> {
        return DefaultKafkaConsumerFactory(consumerConfigs())
    }

    /**
     * 단일 이벤트 처리용 Kafka Listener Container Factory
     * 
     * - Batch Listener: false (단일 이벤트)
     * - Concurrency: 8 (파티션당 1 스레드)
     * - AckMode: MANUAL_IMMEDIATE (수동 커밋, 즉시)
     */
    @Bean
    fun kafkaListenerContainerFactory(): KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<K, V>> {
        val factory = ConcurrentKafkaListenerContainerFactory<K, V>()
        
        factory.consumerFactory = consumerFactory()
        
        // 단일 이벤트 처리
        factory.isBatchListener = kafkaConsumerConfigData.batchListener
        
        // Concurrency 설정 (파티션 수와 동일하게)
        factory.setConcurrency(kafkaConsumerConfigData.concurrencyLevel)
        
        // 자동 시작
        factory.setAutoStartup(kafkaConsumerConfigData.autoStartup)
        
        // Container Properties 설정
        factory.containerProperties.apply {
            pollTimeout = kafkaConsumerConfigData.pollTimeoutMs
            ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            isObservationEnabled = true  // 트레이싱 활성화
        }
        
        return factory
    }

}
