package io.github.hyungkishin.transentia.domain.model

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

        /**
         * 외부에서 사용할 수 있는 유일한 생성 메서드.
         * "123.456"과 같이 소수점 8자리 이하 숫자만 허용됨.
         */
        fun fromDecimalString(input: String): Money {
            require(input.matches(Regex("""^\d+(\.\d{1,8})?$"""))) {
                "소수점 8자리 이하의 숫자만 허용됩니다: $input"
            }
            val parts = input.split(".")
            val whole = parts[0].toLong()
            val fraction = if (parts.size > 1)
                parts[1].padEnd(SCALE, '0').take(SCALE).toLong()
            else 0L
            return fromRawValue(whole * SCALE_FACTOR + fraction)
        }

        /**
         * [infra] 또는 [test] 계층에서 DB 복원/변환 시 사용 가능.
         */
        fun fromRawValue(raw: Long): Money {
            require(raw >= 0L) { "금액은 음수일 수 없습니다." }
            return Money(raw)
        }
    }

    // 내부 도메인에서만 사용하는 연산자들
    internal fun add(other: Money): Money =
        fromRawValue(this.rawValue + other.rawValue)

    internal fun subtractOrThrow(other: Money): Money {
        require(this.rawValue >= other.rawValue) { "금액은 0원 미만이 될 수 없습니다." }
        return fromRawValue(this.rawValue - other.rawValue)
    }

    internal fun isPositive(): Boolean = rawValue > 0L
    internal fun isZero(): Boolean = rawValue == 0L

    override fun compareTo(other: Money): Int = this.rawValue.compareTo(other.rawValue)

    override fun toString(): String {
        val whole = rawValue / SCALE_FACTOR
        val fraction = rawValue % SCALE_FACTOR
        return if (fraction == 0L) {
            whole.toString()
        } else {
            "%d.%0${SCALE}d".format(whole, fraction).trimEnd('0').trimEnd('.')
        }
    }
}
