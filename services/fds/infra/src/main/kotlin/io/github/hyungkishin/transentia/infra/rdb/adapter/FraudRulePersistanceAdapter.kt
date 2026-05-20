package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.required.FraudRuleRepository
import io.github.hyungkishin.transentia.container.model.FraudeRule
import io.github.hyungkishin.transentia.infra.rdb.entity.FraudRuleJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.FraudRuleJpaRepository
import org.springframework.stereotype.Component

@Component
class FraudRulePersistanceAdapter(
    private val jpaRepository: FraudRuleJpaRepository,
) : FraudRuleRepository {
    override fun save(fraudeRule: FraudeRule): Long {
        val entity = FraudRuleJpaEntity.from(fraudeRule)
        return jpaRepository.save(entity).id
    }

    override fun findAllActive(): List<FraudeRule> =
        jpaRepository
            .findByIsActiveTrue()
            .map { it.toDomain() }
}
