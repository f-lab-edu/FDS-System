package io.github.hyungkishin.transentia.relay.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class ExecutorServiceConfig(
    private val config: OutboxRelayConfig
) {

    @Bean("outboxExecutorService")
    fun outboxExecutorService(): ExecutorService {
        return Executors.newFixedThreadPool(config.threadPoolSize)
    }

}
