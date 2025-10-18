package io.github.hyungkishin.transentia.relay.component

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.relay.config.OutboxRelayConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class OutboxItemReader(
    private val repository: TransferEventsOutboxRepository,
    private val config: OutboxRelayConfig
) {
    private val log = LoggerFactory.getLogger(javaClass)
    
    private var items: List<ClaimedRow> = emptyList()
    private var currentIndex: Int = 0

    @Synchronized
    fun read(): ClaimedRow? {
        // 메모리에 읽지 않은 아이템이 남아있으면 반환한다.
        if (currentIndex < items.size) {
            return items[currentIndex++]
        }
        
        fetchItems()
        
        if (items.isEmpty()) {
            return null
        }
        
        return items[currentIndex++]
    }

    private fun fetchItems() {
        try {
            items = repository.claimBatch(
                limit = config.chunkSize,
                now = Instant.now(),
                sendingTimeoutSeconds = config.sendingTimeoutSeconds
            )
            currentIndex = 0
            
            log.debug("Fetched {} items from outbox", items.size)
            
        } catch (e: Exception) {
            log.error("Failed to fetch items from outbox", e)
            items = emptyList()
            currentIndex = 0
        }
    }

    fun reset() {
        items = emptyList()
        currentIndex = 0
    }
}
