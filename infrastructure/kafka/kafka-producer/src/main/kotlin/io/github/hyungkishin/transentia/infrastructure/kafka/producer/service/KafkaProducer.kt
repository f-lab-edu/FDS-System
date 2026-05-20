package io.github.hyungkishin.transentia.infrastructure.kafka.producer.service

import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import org.apache.avro.specific.SpecificRecordBase
import org.springframework.kafka.support.SendResult

interface KafkaProducer<K : Serializable, V : SpecificRecordBase> {
    fun send(
        topicName: String,
        message: V,
        callback: BiConsumer<SendResult<K, V>, Throwable>,
    )

    fun sendSync(
        topicName: String,
        message: V,
    ): SendResult<K, V>

    fun sendAsync(
        topicName: String,
        message: V,
    ): CompletableFuture<SendResult<K, V>>
}
