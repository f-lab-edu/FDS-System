package io.github.hyungkishin.transentia.infra.rdb.adapter

import io.github.hyungkishin.transentia.application.required.RiskAnalysisRepository
import io.github.hyungkishin.transentia.common.snowflake.IdGenerator
import io.github.hyungkishin.transentia.container.enums.FinalDecisionType
import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import io.github.hyungkishin.transentia.container.model.RiskLog
import io.github.hyungkishin.transentia.infra.constants.ActionType
import io.github.hyungkishin.transentia.infra.rdb.entity.FraudDetectionJpaEntity
import io.github.hyungkishin.transentia.infra.rdb.repository.FraudDetectionJpaRepository
import org.springframework.stereotype.Repository

@Repository
class RiskAnalysisPersistenceAdapter(
    private val repository: FraudDetectionJpaRepository,
    private val idGenerator: IdGenerator,
) : RiskAnalysisRepository {
    override fun save(
        event: TransferCompleteEvent,
        riskLog: RiskLog,
        traceId: String?,
    ) {
        val entity =
            FraudDetectionJpaEntity(
                id = idGenerator.nextId(),
                eventId = event.eventId,
                fromAccountId = event.senderId,
                toAccountId = event.receiverId,
                amount = event.amount,
                currency = "KRW",
                totalRiskScore = riskLog.ruleHits.sumOf { it.weight },
                actionType = mapDecision(riskLog.decision),
                ruleResults =
                    riskLog.ruleHits.map { hit ->
                        mapOf(
                            "ruleCode" to hit.ruleCode,
                            "severity" to hit.severity.name,
                            "weight" to hit.weight,
                            "reason" to (hit.reason ?: ""),
                            "occurredAt" to hit.occurredAt.toString(),
                        )
                    },
                detectedAt = riskLog.evaluatedAt,
                traceId = traceId,
            )
        repository.save(entity)
    }

    private fun mapDecision(decision: FinalDecisionType): ActionType =
        when (decision) {
            FinalDecisionType.ALLOWED -> ActionType.ALLOW
            FinalDecisionType.REVIEW -> ActionType.REVIEW
            FinalDecisionType.BLOCKED -> ActionType.BLOCK
        }
}
