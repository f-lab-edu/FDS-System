package io.github.hyungkishin.transentia.infrastructure.kafka.producer.exception

class KafkaProducerException(
    message: String,
    exception: Exception,
) : RuntimeException(message, exception)
