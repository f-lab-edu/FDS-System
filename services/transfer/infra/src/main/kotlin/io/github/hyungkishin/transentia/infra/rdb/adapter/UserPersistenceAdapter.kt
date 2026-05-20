package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.required.UserRepository
import io.github.hyungkishin.transentia.container.model.user.User
import io.github.hyungkishin.transentia.infra.rdb.entity.AccountBalanceJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.entity.UserJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.UserJpaRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component

@Component
class UserPersistenceAdapter(
    private val jpaRepository: UserJpaRepository,
) : UserRepository {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun findById(id: Long): User? = jpaRepository.findByIdWithAccount(id)?.toDomain()

    override fun findByAccountNumber(accountNumber: String): User? =
        jpaRepository.findByAccountNumberWithAccountBalances(accountNumber)?.toDomain()

    /**
     * 패시미스틱 락으로 사용자 조회
     *
     * 1단계: Native Query로 user_id를 FOR UPDATE 락과 함께 조회 (락 획득)
     * 2단계: 획득한 user_id로 Entity 조회
     *
     * 이렇게 하면 Hibernate의 "follow-on locking" 문제를 회피하고
     * 원자적으로 락을 획득할 수 있다.
     */
    override fun findByAccountNumberWithLock(accountNumber: String): User? {
        // 1단계: FOR UPDATE로 user_id 조회 (락 획득)
        val userId =
            jpaRepository.findUserIdByAccountNumberForUpdate(accountNumber)
                ?: return null

        // 2단계: 락이 걸린 상태에서 Entity 조회 (영속성 컨텍스트에 등록)
        return jpaRepository.findByIdWithAccount(userId)?.toDomain()
    }

    /**
     * 사용자 저장 (Dirty Checking 활용)
     *
     * 동작 원리:
     * 1. 영속성 컨텍스트에 기존 Entity가 있으면 (findByAccountNumberWithLock으로 조회된 경우)
     *    → 해당 Entity의 필드를 수정하고 dirty checking으로 UPDATE
     * 2. 영속성 컨텍스트에 없으면 (신규 생성 또는 다른 트랜잭션에서 조회된 경우)
     *    → JpaRepository.save()로 처리 (merge 또는 persist)
     *
     * 이렇게 하면:
     * - 동시성 제어가 필요한 송금 트랜잭션: FOR UPDATE 락 유지 상태에서 UPDATE
     * - 초기 데이터 생성 등: 기존 방식대로 동작
     */
    override fun save(user: User): User {
        // 1차 캐시에서 기존 Entity 조회 시도
        val existingUserEntity = entityManager.find(UserJpaEntity::class.java, user.id.value)

        if (existingUserEntity != null) {
            // 기존 Entity가 영속성 컨텍스트에 있음 → dirty checking 활용
            syncUserEntity(existingUserEntity, user)
            return existingUserEntity.toDomain()
        }

        // 영속성 컨텍스트에 없음 → JpaRepository.save() 사용
        // (신규 생성이거나 MockDataInitializer 등에서 호출된 경우)
        val entity = UserJpaEntity.from(user)
        return jpaRepository.save(entity).toDomain()
    }

    /**
     * Domain 객체의 변경사항을 JPA Entity에 동기화
     */
    private fun syncUserEntity(
        entity: UserJpaEntity,
        domain: User,
    ) {
        // AccountBalance 동기화 (잔액 변경)
        val existingAccountEntity =
            entityManager.find(
                AccountBalanceJpaEntity::class.java,
                domain.accountBalance.id.value,
            )

        if (existingAccountEntity != null) {
            existingAccountEntity.balance = domain.accountBalance.current().minor
        }
    }
}
