package io.github.hyungkishin.transentia.application.required

import java.time.Instant

/**
 * 특정 사용자의 since 이후 송금 횟수를 조회하는 포트.
 * RAPID_TRANSFER 룰이 호출하며, 실제 어댑터는 별도 PR 에서 JPA/Read-Model 로 구현 예정.
 */
interface RecentTransferCountQueryPort {
    fun countByUserSince(userId: Long, since: Instant): Long
}
