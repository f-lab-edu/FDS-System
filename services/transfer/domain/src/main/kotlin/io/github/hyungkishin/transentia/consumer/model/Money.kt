package io.github.hyungkishin.transentia.consumer.model

/**
 * 도메인 내에서 금액을 표현하는 불변 값 객체.
 *
 * - SCALE = 8 고정 소수점 기반 Long 값으로 구성됨
 * - 음수 금액은 생성 및 연산 불가
 * - 외부에서는 fromDecimalString()으로만 생성할 것
 */
@JvmInline
value class Money private constructor(val rawValue: Long) : Comparable<Money> {

    companion object {
        private const val SCALE = 8
        private const val SCALE_FACTOR = 100_000_000L

        // Regex 는 Pattern 컴파일을 하기 때문에 비싼 연산 이므로, 캐싱한다.
        private val DECIMAL_RE = Regex("""^\d+(\.\d{1,8})?$""")

        fun fromDecimalString(input: String): Money {
            require(DECIMAL_RE.matches(input)) { "소수점 8자리 이하의 숫자만 허용됩니다: $input" }

            val parts = input.split(".")
            val whole = parts[0].toLong()
            val fraction = if (parts.size > 1)
                parts[1].padEnd(SCALE, '0').take(SCALE).toLong()
            else 0L
            return fromRawValue(whole * SCALE_FACTOR + fraction)
        }

        fun fromRawValue(raw: Long): Money {
            require(raw >= 0L) { "금액은 음수일 수 없습니다." }
            return Money(raw)
        }
    }

    val minor: Long get() = rawValue

    // 도메인 규칙에 의해 잔액 검사는 밖에서 하고, 순수 연산만 진행한다
    internal fun add(other: Money): Money {
        // overflow guard
        require(rawValue <= Long.MAX_VALUE - other.rawValue) { "금액 덧셈 오버플로우" }
        return fromRawValue(rawValue + other.rawValue)
    }

    internal fun subtract(other: Money): Money {
        // underflow guard (음수 금지)
        require(rawValue >= other.rawValue) { "금액은 0원 미만이 될 수 없습니다." }
        return fromRawValue(rawValue - other.rawValue)
    }

    internal fun isPositive(): Boolean = rawValue > 0L
    internal fun isZero(): Boolean = rawValue == 0L

    override fun compareTo(other: Money): Int = rawValue.compareTo(other.rawValue)

    override fun toString(): String {
        val whole = rawValue / SCALE_FACTOR
        val fraction = rawValue % SCALE_FACTOR
        return if (fraction == 0L) whole.toString()
        else "%d.%0${SCALE}d".format(whole, fraction).trimEnd('0').trimEnd('.')
    }
}