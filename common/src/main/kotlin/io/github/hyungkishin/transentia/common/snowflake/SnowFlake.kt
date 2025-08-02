package io.github.hyungkishin.transentia.common.snowflake

class Snowflake(
    private val nodeId: Long,
    private val customEpoch: Long = 1704067200000L // 2024-01-01T00:00:00Z
) {
    companion object {
        private const val UNUSED_BITS = 1
        private const val EPOCH_BITS = 41
        private const val NODE_ID_BITS = 10
        private const val SEQUENCE_BITS = 12

        private const val maxNodeId = (1L shl NODE_ID_BITS) - 1
        private const val maxSequence = (1L shl SEQUENCE_BITS) - 1
    }

    init {
        require(nodeId in 0..maxNodeId) { "Node ID must be between 0 and $maxNodeId" }
    }

    @Volatile private var lastTimestamp = -1L
    @Volatile private var sequence = 0L

    @Synchronized
    fun nextId(): Long {
        var current = timestamp()

        if (current < lastTimestamp) {
            throw IllegalStateException("Clock moved backwards. Refusing to generate id")
        }

        if (current == lastTimestamp) {
            sequence = (sequence + 1) and maxSequence
            if (sequence == 0L) current = waitNextMillis(current)
        } else {
            sequence = 0L
        }

        lastTimestamp = current

        return ((current - customEpoch) shl (NODE_ID_BITS + SEQUENCE_BITS)) or
                (nodeId shl SEQUENCE_BITS) or
                sequence
    }

    private fun timestamp() = System.currentTimeMillis()

    private fun waitNextMillis(current: Long): Long {
        var now = current
        while (now <= lastTimestamp) {
            now = timestamp()
        }
        return now
    }
}