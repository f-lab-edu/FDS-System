package io.github.hyungkishin.transentia.relay.component

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.relay.config.OutboxRelayConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@Component
class OutboxItemReader(
    private val repository: TransferEventsOutboxRepository,
    private val config: OutboxRelayConfig
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock(true)
    
    private var items: List<ClaimedRow> = emptyList()
    private var currentIndex: Int = 0

    fun read(): ClaimedRow? {
        lock.lock()
        try {
            if (currentIndex < items.size) {
                return items[currentIndex++]
            }
        } finally {
            lock.unlock()
        }
        
        return fetchAndRead()
    }

    private fun fetchAndRead(): ClaimedRow? {
        val acquired = lock.tryLock(5, TimeUnit.SECONDS)
        if (!acquired) {
            log.warn("DB 조회 락 획득 실패 (다른 워커가 이미 조회 중)")
            return null
        }
        
        try {
            if (currentIndex < items.size) {
                return items[currentIndex++]
            }
            
            fetchItems()
            
            if (items.isEmpty()) {
                return null
            }
            
            return items[currentIndex++]
            
        } finally {
            lock.unlock()
        }
    }

    private fun fetchItems() {
        try {
            items = repository.claimBatch(
                limit = config.chunkSize,
                now = Instant.now(),
                sendingTimeoutSeconds = config.sendingTimeoutSeconds
            )
            currentIndex = 0
            
            if (items.isNotEmpty()) {
                log.debug("DB 조회: {} 건", items.size)
            }
            
        } catch (e: Exception) {
            log.error("DB 조회 실패", e)
            items = emptyList()
            currentIndex = 0
        }
    }

    fun reset() {
        lock.lock()
        try {
            items = emptyList()
            currentIndex = 0
        } finally {
            lock.unlock()
        }
    }
}
