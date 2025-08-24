package io.github.hyungkishin.transentia.application.required.event

interface EventPublisherPort {
    fun publish(event: Any)
}