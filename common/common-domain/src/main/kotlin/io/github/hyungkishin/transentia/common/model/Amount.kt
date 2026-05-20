package io.github.hyungkishin.transentia.common.model

/**
 * 통화 단위가 포함된 금액 (Money + Currency)
 */
data class Amount(
    val money: Money,
    val currency: Currency,
) : Comparable<Amount> {
    companion object {
        /**
         * DB(Long) <-> 도메인 변환에 쓰는 생성자 ( 단위는 그대로 유지한다. )
         * */
        fun fromMinor(
            minor: Long,
            currency: Currency,
        ) = Amount(Money.fromRawValue(minor), currency)

        /**
         * 정수 원 / 달러 / 코인 에서 생성
         * */
        fun fromMajor(
            major: Long,
            currency: Currency,
        ) = Amount(Money.fromMajor(major, currency.scale), currency)

        /**
         * 10000.25 같은 문자열에서 생성
         * */
        fun parse(
            input: String,
            currency: Currency,
        ) = Amount(Money.parseMajorString(input, currency.scale), currency)
    }

    val minor: Long get() = money.rawValue

    fun add(other: Amount): Amount {
        requireSame(other)
        return copy(money = money.add(other.money))
    }

    fun subtract(other: Amount): Amount {
        requireSame(other)
        return copy(money = money.subtract(other.money))
    }

    override fun compareTo(other: Amount): Int {
        requireSame(other)
        return money.compareTo(other.money)
    }

    private fun requireSame(other: Amount) {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
    }

    fun isPositive(): Boolean = money.isPositive()

    fun isZero(): Boolean = money.isZero()

    override fun toString(): String = money.toString(currency.scale) + " " + currency.name
}
