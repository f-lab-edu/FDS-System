package io.github.hyungkishin.transentia.container.model.user

data class TransferLockReason(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "송금 제한 사유는 비어있을 수 없습니다" }
        require(value.length <= 500) { "송금 제한 사유는 500자를 초과할 수 없습니다" }
    }

    companion object {
        fun fraud() = TransferLockReason("사기 의심 거래로 인한 제한")

        fun admin(reason: String) = TransferLockReason("관리자 제재: $reason")

        fun suspicious() = TransferLockReason("의심스러운 거래 패턴 감지")
    }
}
