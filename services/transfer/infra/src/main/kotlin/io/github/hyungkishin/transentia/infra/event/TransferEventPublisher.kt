package io.github.hyungkishin.transentia.infra.event

import io.github.hyungkishin.transentia.application.required.event.EventPublisherPort
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class TransferEventPublisher(
    private val delegate: ApplicationEventPublisher
) : EventPublisherPort {

    override fun publish(event: Any) {
        // 트랜잭션 안에서 발행한다. ( 리스너가 AFTER_COMMIT 으로 보증하기 위함 )
        delegate.publishEvent(event)
    }

}