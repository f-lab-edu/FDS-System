package io.github.hyungkishin.transentia.infra.snowflake

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors


class SnowflakeTest {

    private val nodeId = 42L
    private val customEpoch = 1704067200000L // 2024-01-01T00:00:00Z
    private val maxClockBackwardMs = 5L

    private val snowflake = Snowflake(
        nodeId = nodeId,
        customEpoch = customEpoch,
        maxClockBackwardMs = maxClockBackwardMs
    )

    @Test
    fun `ID 생성 시 timestamp, nodeId, sequence가 포함되어야 한다`() {
        val id = snowflake.nextId()
        val (timestamp, parsedNodeId, sequence) = snowflake.parseId(id)

        assertEquals(nodeId, parsedNodeId)
        assertTrue(timestamp >= customEpoch)
        assertTrue(sequence >= 0)
    }

    @Test
    fun `ID는 시간 순으로 증가해야 한다`() {
        val id1 = snowflake.nextId()
        val id2 = snowflake.nextId()
        val id3 = snowflake.nextId()

        assertTrue(id1 < id2)
        assertTrue(id2 < id3)
    }

    @Test
    fun `동시성 환경에서도 모든 ID는 유일해야 한다`() {
        val threads = 32
        val perThread = 10_000
        val total = threads * perThread

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val ids = ConcurrentHashMap.newKeySet<Long>()

        repeat(threads) {
            pool.submit {
                try {
                    start.await()
                    repeat(perThread) {
                        ids.add(snowflake.nextId())
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()

        assertEquals(total, ids.size, "모든 ID는 고유해야 합니다.")
    }

    @Test
    fun `생성된 ID는 다시 정확히 파싱 가능해야 한다`() {
        val id = snowflake.nextId()
        val (timestamp, parsedNodeId, sequence) = snowflake.parseId(id)

        assertEquals(nodeId, parsedNodeId)
        assertTrue(timestamp >= customEpoch)
        assertTrue(sequence >= 0)
    }
}