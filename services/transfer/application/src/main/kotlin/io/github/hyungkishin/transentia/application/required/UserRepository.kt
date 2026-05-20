package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.container.model.user.User

interface UserRepository {
    fun findById(id: Long): User?

    fun findByAccountNumber(accountNumber: String): User?

    /**
     * 계좌번호로 사용자 조회 (패시미스틱 락 - FOR UPDATE)
     * 송금 시 동시성 제어를 위해 사용
     * Native Query로 FOR UPDATE 락을 건 후 Entity 조회
     */
    fun findByAccountNumberWithLock(accountNumber: String): User?

    fun save(user: User): User
}
