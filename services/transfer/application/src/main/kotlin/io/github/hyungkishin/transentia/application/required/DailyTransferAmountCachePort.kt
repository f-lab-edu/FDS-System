package io.github.hyungkishin.transentia.application.required

/**
 * 사용자의 일일 누적 송금액을 관리하는 캐시 포트.
 * 키 정책은 어댑터가 책임진다(예: daily:transfer:{userId}:{yyyyMMdd}, TTL ~26h).
 */
interface DailyTransferAmountCachePort {
    /** 오늘 누적 송금액(rawValue) 반환. 없으면 0. */
    fun getTodayAmount(userId: Long): Long

    /** 오늘 누적치에 amount(rawValue)를 더한 뒤 갱신된 누적치 반환. 최초 호출 시 TTL 도 설정. */
    fun addTodayAmount(userId: Long, amount: Long): Long
}
