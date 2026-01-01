package io.github.hyungkishin.transentia.infra.rdb.repository

import io.github.hyungkishin.transentia.infra.rdb.entity.UserJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserJpaRepository: JpaRepository<UserJpaEntity, Long> {

    @Query(
        """
            SELECT u 
            FROM UserJpaEntity u 
            LEFT JOIN FETCH u.account 
            WHERE u.id = :id
        """
    )
    fun findByIdWithAccount(@Param("id") id: Long): UserJpaEntity?

    /**
     * 계좌번호로 User + Account 조회
     */
    @Query(
        """
            SELECT u 
            FROM UserJpaEntity u 
            LEFT JOIN FETCH u.account a 
            WHERE a.accountNumber = :accountNumber
        """
    )
    fun findByAccountNumberWithAccountBalances(@Param("accountNumber") accountNumber: String): UserJpaEntity?

    /**
     * 계좌번호로 User ID 조회 (패시미스틱 락 - Native Query)
     * 
     * Hibernate의 @Lock + JOIN FETCH는 "follow-on locking" 문제로 
     * 락 획득이 원자적이지 않아 데드락이 발생할 수 있다.
     * Native Query로 직접 FOR UPDATE를 걸어 원자적 락 획득 보장.
     */
    @Query(
        value = """
            SELECT u.id 
            FROM users u 
            INNER JOIN account_balances a ON u.id = a.user_id 
            WHERE a.account_number = :accountNumber 
            FOR UPDATE
        """,
        nativeQuery = true
    )
    fun findUserIdByAccountNumberForUpdate(@Param("accountNumber") accountNumber: String): Long?

}