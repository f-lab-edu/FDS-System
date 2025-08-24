package io.github.hyungkishin.transentia.infra.event

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TopicConfig {

    /**
     * 송금 완료 이벤트 토픽
     */
    @Bean
    fun transferCompletedTopic(): NewTopic =
        NewTopic("transfer.completed", 1, 1.toShort())

    /**
     * 송금 실패 이벤트 토픽
     */
    @Bean
    fun transferFailedTopic(): NewTopic =
        NewTopic("transfer.failed", 1, 1.toShort())
}
