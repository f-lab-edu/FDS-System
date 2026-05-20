package io.github.hyungkishin.transentia.common.model

/**
 * 시스템에서 지원하는 통화 정의
 *
 * - 각 통화는 소수 자릿수(scale)를 가진다
 * - scaleFactor = 10^scale (Money.rawValue 보정에 사용)
 */
enum class Currency(
    val scale: Int,
) {
    KRW(0), // 원화: 소수 없음
    USD(2), // 달러: 소수 2자리
    EUR(2), // 유로: 소수 2자리
    JPY(0), // 엔화: 소수 없음
    ;

    val scaleFactor: Long = pow10(scale)
}

private fun pow10(n: Int): Long {
    var r = 1L
    repeat(n) { r *= 10 }
    return r
}
