package io.github.hyungkishin.transentia.relay.batch

import io.github.hyungkishin.transentia.application.required.TransferEventsOutboxRepository
import io.github.hyungkishin.transentia.common.outbox.transfer.ClaimedRow
import io.github.hyungkishin.transentia.relay.config.OutboxRelayConfig
import org.slf4j.LoggerFactory
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.ItemReader
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
@StepScope
class TransferOutboxItemReader(
    private val repository: TransferEventsOutboxRepository,
    private val config: OutboxRelayConfig
) : ItemReader<ClaimedRow> {

    private val log = LoggerFactory.getLogger(javaClass)

    // Thread-Safe Queue
    private val queue = ConcurrentLinkedQueue<ClaimedRow>()

    // 배치 로딩 Lock (한 번에 한 스레드만 로딩)
    private val loadLock = ReentrantLock()

    // 더 이상 읽을 데이터가 없는지 여부
    @Volatile
    private var exhausted = false

    override fun read(): ClaimedRow? {
        // Queue에서 데이터 꺼내기
        val item = queue.poll()

        if (item != null) {
            return item
        }

        // Queue가 비었고, 이미 모든 데이터를 읽었으면 종료
        if (exhausted) {
            return null
        }

        // Queue가 비었으면 새 배치 로드 시도
        return loadLock.withLock {
            // Double-check: 다른 스레드가 이미 로드했을 수 있음
            val recheck = queue.poll()
            if (recheck != null) {
                return recheck
            }

            // 새 배치 로드
            loadNextBatch()

            // 로드 후 다시 시도
            queue.poll()
        }
    }

    private fun loadNextBatch() {
        try {
            val batch = repository.claimBatch(
                limit = config.chunkSize,
                now = Instant.now(),
                sendingTimeoutSeconds = config.sendingTimeoutSeconds
            )

            if (batch.isEmpty()) {
                exhausted = true
                log.debug("더 이상 처리할 이벤트가 없습니다")
            } else {
                queue.addAll(batch)
                log.debug("새 배치 로드: {} 건", batch.size)
            }

        } catch (e: Exception) {
            log.error("배치 로드 실패", e)
            exhausted = true
        }
    }

    /**
     * Step 재시작 시 상태 초기화
     */
    fun reset() {
        queue.clear()
        exhausted = false
        log.debug("Reader 상태 초기화")
    }
}
