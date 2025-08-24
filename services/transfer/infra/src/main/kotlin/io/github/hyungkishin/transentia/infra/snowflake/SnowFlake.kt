package io.github.hyungkishin.transentia.infra.snowflake

import java.util.concurrent.atomic.AtomicLong

/**
 * Snowflake (Lock-Free, CAS)
 *  - nodeId: 0..1023
 *  - customEpoch: 기준 시각(ms), 기본 2024-01-01 UTC
 *  - maxClockBackwardMs: 허용 역행(ms), 0이면 역행 즉시 예외
 */
class Snowflake(
    private val nodeId: Long,
    private val customEpoch: Long = 1704067200000L,
    private val maxClockBackwardMs: Long = 5L
) {
    companion object {
        private const val NODE_ID_BITS = 10
        private const val SEQUENCE_BITS = 12
        private const val TIME_SHIFT   = NODE_ID_BITS + SEQUENCE_BITS

        private const val MAX_NODE_ID = (1L shl NODE_ID_BITS) - 1
        private const val MAX_SEQUENCE = (1L shl SEQUENCE_BITS) - 1
    }

    init {
        require(nodeId in 0..MAX_NODE_ID) { "nodeId must be 0..$MAX_NODE_ID" }
        require(maxClockBackwardMs >= 0) { "maxClockBackwardMs must be >= 0" }
    }

    /**
     * packed state: [ timestamp(ms) << SEQUENCE_BITS | sequence ]
     * 초기값은 0L (ts=0, seq=0) 로 둔다.
     * 음수로 두면 ushr 시 큰 양수로 보이는 문제가 생김.
     */
    private val state = AtomicLong(0L)

    fun nextId(): Long {
        while (true) {
            val prev = state.get()
            val prevTs = prev ushr SEQUENCE_BITS
            val prevSeq = prev and MAX_SEQUENCE

            var now = currentTime()
            if (now < prevTs) {
                val diff = prevTs - now // 원본 ms 기준으로만 비교
                if (diff > maxClockBackwardMs) {
                    throw IllegalStateException("Clock moved backwards by ${diff}ms (> $maxClockBackwardMs)")
                }
                // 허용 범위면 이전 ts로 끌어올려 단조 증가 보장
                now = prevTs
            }

            val (nextTs, nextSeq) =
                if (now == prevTs) {
                    val s = (prevSeq + 1) and MAX_SEQUENCE
                    if (s == 0L) {
                        // 같은 ms에서 시퀀스 고갈 → 다음 ms까지 스핀 대기
                        var next = currentTime()
                        while (next <= prevTs) {
                            Thread.onSpinWait()
                            next = currentTime()
                        }
                        next to 0L
                    } else {
                        now to s
                    }
                } else {
                    // 새로운 ms → seq 리셋
                    now to 0L
                }

            val nextPacked = (nextTs shl SEQUENCE_BITS) or nextSeq
            if (state.compareAndSet(prev, nextPacked)) {
                val idTs = nextTs - customEpoch
                return (idTs shl TIME_SHIFT) or (nodeId shl SEQUENCE_BITS) or nextSeq
            }
            // 경합 시 재시도
            Thread.onSpinWait()
        }
    }

    /** ID → (timestamp(ms), nodeId, sequence) */
    fun parseId(id: Long): Triple<Long, Long, Long> {
        val seq = id and MAX_SEQUENCE
        val nid = (id ushr SEQUENCE_BITS) and ((1L shl NODE_ID_BITS) - 1)
        val ts  = (id ushr TIME_SHIFT) + customEpoch
        return Triple(ts, nid, seq)
    }

    private fun currentTime(): Long = System.currentTimeMillis()
}
