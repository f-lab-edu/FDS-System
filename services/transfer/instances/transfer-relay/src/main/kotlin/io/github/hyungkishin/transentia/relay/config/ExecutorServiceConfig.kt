package io.github.hyungkishin.transentia.relay.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class ExecutorServiceConfig(
    private val config: OutboxRelayConfig
) {

    @Bean("outboxExecutorService")
    fun outboxExecutorService(): ExecutorService {
        return ThreadPoolExecutor(
            config.threadPoolSize,
            config.threadPoolSize,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(500),
            outboxThreadFactory(),
            ThreadPoolExecutor.CallerRunsPolicy()
        )
    }

    private fun outboxThreadFactory(): ThreadFactory {
        return object : ThreadFactory {
            private val threadNumber = AtomicInteger(1)

            override fun newThread(r: Runnable): Thread {
                val thread = Thread(r, "outbox-worker-${threadNumber.getAndIncrement()}")
                thread.isDaemon = false
                thread.priority = Thread.NORM_PRIORITY
                return thread
            }
        }
    }
}
