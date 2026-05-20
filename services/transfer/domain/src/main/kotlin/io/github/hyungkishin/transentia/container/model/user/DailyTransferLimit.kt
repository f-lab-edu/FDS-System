package io.github.hyungkishin.transentia.container.model.user

data class DailyTransferLimit(
    val value: Long,
) {
    init {
        require(value >= 0) { "일일 송금 한도는 0 이상이어야 합니다" }
        require(value <= MAX_LIMIT) { "일일 송금 한도는 최대 ${MAX_LIMIT}원까지 설정 가능합니다" }
    }

    companion object {
        private const val MAX_LIMIT = 100_000_000L // 1억원

        fun basic() = DailyTransferLimit(5_000_000L) // 500만원

        fun premium() = DailyTransferLimit(20_000_000L) // 2000만원

        fun vip() = DailyTransferLimit(50_000_000L) // 5000만원

        fun admin() = DailyTransferLimit(MAX_LIMIT) // 1억원

        fun of(amount: Long) = DailyTransferLimit(amount)
    }

    fun isExceeded(amount: Long): Boolean = amount > value

    fun canTransfer(amount: Long): Boolean = amount <= value
}
