package io.github.hyungkishin.transentia.common.snowflake

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnowflakeTest {

    @Test
    fun `ID는 항상 고유하고 증가해야 한다`() {
        val sf = Snowflake(nodeId = 1L)
        val id1 = sf.nextId()
        val id2 = sf.nextId()
        assertTrue(id2 > id1)
    }

    @Test
    fun `노드 ID가 범위를 벗어나면 예외 발생`() {
        assertThrows<IllegalArgumentException> {
            Snowflake(nodeId = 2048L) // 10비트 범위 초과
        }
    }
}
