package io.github.hyungkishin.transentia.infrastructure.kafka.producer.service.impl

import io.github.hyungkishin.transentia.infrastructure.kafka.producer.exception.KafkaProducerException
import io.github.hyungkishin.transentia.infrastructure.kafka.producer.service.KafkaProducer
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import org.apache.avro.specific.SpecificRecordBase
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service

@Service
class KafkaProducerImpl<K : Serializable, V : SpecificRecordBase>(
    private val kafkaTemplate: KafkaTemplate<K, V>,
) : KafkaProducer<K, V> {
    companion object {
        private val log = LoggerFactory.getLogger(KafkaProducerImpl::class.java)
    }

    // 비동기 메서드 (하위 호환성 유지)
    override fun send(
        topicName: String,
        message: V,
        callback: BiConsumer<SendResult<K, V>, Throwable>,
    ) {
        log.info("Sending message={} to topic={}", message, topicName)

        try {
            kafkaTemplate.send(topicName, message).whenComplete { result, ex ->
                callback.accept(result, ex)
            }
        } catch (e: Exception) {
            log.error("Error on kafka producer with key={}, message={} and exception={}", message, e)
            throw KafkaProducerException("Error on kafka producer with message=$message", e)
        }
    }

    // 동기 메서드
    override fun sendSync(
        topicName: String,
        message: V,
    ): SendResult<K, V> {
        log.info("Sending message={} to topic={} synchronously", message, topicName)

        try {
            return kafkaTemplate.send(topicName, message).get()
        } catch (e: Exception) {
            log.error("Error on synchronous kafka producer with key={}, message={} and exception={}", message, e)
            throw KafkaProducerException("Error on synchronous kafka producer with message=$message", e)
        }
    }

    override fun sendAsync(
        topicName: String,
        message: V,
    ): CompletableFuture<SendResult<K, V>> = kafkaTemplate.send(topicName, message)
}
