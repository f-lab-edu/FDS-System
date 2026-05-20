package io.github.hyungkishin.transentia.common.model

/**
 * 소수점 8자리 고정 스케일 기반의 불변 금액 값 객체.
 *
 * - SCALE = 8
 * - rawValue는 Long으로 저장됨 (소수점 제거된 상태)
 * - 순수한 수학적 금액만 표현, 통화 정보 없음
 */
@JvmInline
value class Money private constructor(
    val rawValue: Long,
) : Comparable<Money> {
    companion object {
        fun fromRawValue(raw: Long): Money {
            require(raw >= 0) { "금액은 음수일 수 없습니다." }
            return Money(raw)
        }

        fun fromMajor(
            major: Long,
            scale: Int,
        ): Money = fromRawValue(major * pow10(scale))

        fun parseMajorString(
            input: String,
            scale: Int,
        ): Money {
            if (scale == 0) {
                require(Regex("""^\d+$""").matches(input)) { "정수만 허용: $input" }
                return fromRawValue(input.toLong())
            }

            val re = Regex("""^\d+(\.\d{1,$scale})?$""")
            require(re.matches(input)) { "소수점 ${scale}자리 이하만 허용: $input" }
            val parts = input.split(".")
            val whole = parts[0].toLong()
            val fraction =
                if (parts.size > 1) {
                    parts[1].padEnd(scale, '0').take(scale).toLong()
                } else {
                    0L
                }
            return fromRawValue(whole * pow10(scale) + fraction)
        }
    }

    fun add(other: Money): Money {
        require(rawValue <= Long.MAX_VALUE - other.rawValue) { "덧셈 오버플로우" }
        return Money(rawValue + other.rawValue)
    }

    fun subtract(other: Money): Money {
        require(rawValue >= other.rawValue) { "금액은 음수가 될 수 없습니다." }
        return Money(rawValue - other.rawValue)
    }

    override fun compareTo(other: Money): Int = rawValue.compareTo(other.rawValue)

    fun isPositive(): Boolean = rawValue > 0L

    fun isZero(): Boolean = rawValue == 0L

    fun toString(scale: Int): String {
        if (scale == 0) return rawValue.toString()
        val factor = pow10(scale)
        val whole = rawValue / factor
        val fraction = rawValue % factor
        return if (fraction == 0L) {
            whole.toString()
        } else {
            "%d.%0${scale}d".format(whole, fraction).trimEnd('0').trimEnd('.')
        }
    }
}

private fun pow10(n: Int): Long {
    var r = 1L
    repeat(n) { r *= 10 }
    return r
}
