package io.github.hyungkishin.transentia.container.model.user

data class UserName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "사용자 이름은 비어있을 수 없습니다" }
        require(value.length in 2..50) { "사용자 이름은 2-50자 사이여야 합니다" }
    }
}
