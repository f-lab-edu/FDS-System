package io.github.hyungkishin.transentia.container.model.user

data class Email(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "이메일은 비어있을 수 없습니다" }
        require(isValidEmail(value)) { "올바른 이메일 형식이 아닙니다: $value" }
    }

    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        return email.matches(emailRegex.toRegex())
    }
}
