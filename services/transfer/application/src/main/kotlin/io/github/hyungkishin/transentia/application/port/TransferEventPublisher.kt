package io.github.hyungkishin.transentia.application.port

import io.github.hyungkishin.transentia.common.message.transfer.TransferCompleted

/**
 * 송금 이벤트 발행 Port
 */
interface TransferEventPublisher {
    /**
     * 송금 완료 이벤트 발행
     * 
     * 호출자가 이미 비동기 스레드에서 실행 중
     */
    fun publish(event: TransferCompleted)
}
