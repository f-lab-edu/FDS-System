package io.github.hyungkishin.transentia.application.service

import io.github.hyungkishin.transentia.application.required.AiScoreProvider
import io.github.hyungkishin.transentia.application.required.FraudRuleRepository
import io.github.hyungkishin.transentia.application.required.RecentTransferCountQueryPort
import io.github.hyungkishin.transentia.application.required.RiskAnalysisRepository
import io.github.hyungkishin.transentia.container.enums.FinalDecisionType
import io.github.hyungkishin.transentia.container.enums.RuleSeverity
import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import io.github.hyungkishin.transentia.container.model.FraudeRule
import io.github.hyungkishin.transentia.container.model.RiskLog
import io.github.hyungkishin.transentia.container.model.RiskRuleHit
import java.time.Instant
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AnalyzeTransferService(
    private val fraudRuleRepository: FraudRuleRepository,
    private val recentTransferCountQueryPort: RecentTransferCountQueryPort,
    private val aiScoreProvider: AiScoreProvider,
    private val riskAnalysisRepository: RiskAnalysisRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun analyze(event: TransferCompleteEvent): RiskLog {
        log.info("@@@@@@[FDS] 송금 분석 시작 - transferId={}, amount={}", event.eventId, event.amount)

        // 후속 작업 (코드 외부에서 추적):
        // - Redis 캐싱: 활성 룰 조회 캐시 — 별도 PR 후보, LIMITATIONS 의 P2.
        // - ML 어댑터: AiScoreProvider 의 ES dense_vector kNN 실 구현 — docs/etc/ml-anomaly-detection-poc.md
        // - 엣지케이스 알림: suspicious_pattern_alerts 영속화 완료, WebSocket/Slack 어댑터 — LIMITATIONS 1.3.
        // - 빅데이터 평가: 10년치 이력 + LLM 기반 그래프 임베딩 — E5 이후 후속 실험.

        // 모든 활성화된 룰 조회
        val activeRules = fraudRuleRepository.findAllActive()

        // 각 룰 실행 및 위반 감지
        val ruleHits =
            activeRules.mapNotNull { rule ->
                evaluateRule(rule, event)
            }

        // 최종 판정
        val decision = determineDecision(ruleHits)
        val reasons = ruleHits.map { it.ruleCode }

        // RiskLog 생성
        val riskLog =
            RiskLog.of(
                txId = event.eventId,
                decision = decision,
                reasons = reasons,
                aiScore = aiScoreProvider.score(event),
                ruleHits = ruleHits,
            )

        // 분석 결과 영속화 (같은 트랜잭션)
        riskAnalysisRepository.save(event, riskLog, MDC.get("traceId"))

        log.info(
            "@@@@@@@[FDS] 분석 완료 - transferId={}, decision={}, hitCount={}",
            event.eventId,
            decision,
            ruleHits.size,
        )

        return riskLog
    }

    private fun evaluateRule(
        rule: FraudeRule,
        event: TransferCompleteEvent,
    ): RiskRuleHit? =
        when (rule.ruleType) {
            "HIGH_AMOUNT" -> checkHighAmount(rule, event)
            "SINGLE_HIGH_AMOUNT" -> checkSingleHighAmount(rule, event)
            "RAPID_TRANSFER" -> checkRapidTransfer(rule, event)
            else -> null
        }

    /**
     * 단일 거래 2000만원 이상 탐지
     */
    private fun checkSingleHighAmount(
        rule: FraudeRule,
        event: TransferCompleteEvent,
    ): RiskRuleHit? {
        val threshold = (rule.threshold["amount"] as? Number)?.toLong() ?: return null

        return if (event.amount >= threshold) {
            RiskRuleHit(
                txId = event.eventId,
                ruleCode = "SINGLE_HIGH_AMOUNT",
                severity = RuleSeverity.CRITICAL, // RuleSeverity 에 CRITICAL 추가 추천
                weight = rule.weight.toInt(),
                reason = "단일 거래 고액 감지: ${event.amount} ≥ $threshold",
                occurredAt = Instant.now(),
            )
        } else {
            null
        }
    }

    /**
     * 고액 송금 탐지
     */
    private fun checkHighAmount(
        rule: FraudeRule,
        event: TransferCompleteEvent,
    ): RiskRuleHit? {
        val threshold = (rule.threshold["amount"] as? Number)?.toLong() ?: return null

        return if (event.amount > threshold) {
            RiskRuleHit(
                txId = event.eventId,
                ruleCode = "HIGH_AMOUNT",
                severity = RuleSeverity.HIGH,
                weight = rule.weight.toInt(),
                reason = "고액 송금 감지: ${event.amount} > $threshold",
                occurredAt = Instant.now(),
            )
        } else {
            null
        }
    }

    /**
     * 단기간 다중 송금 탐지
     * threshold.windowMinutes 분 내 sender 의 송금 횟수가 threshold.maxCount 초과 시 위반.
     */
    private fun checkRapidTransfer(
        rule: FraudeRule,
        event: TransferCompleteEvent,
    ): RiskRuleHit? {
        val windowMinutes = (rule.threshold["windowMinutes"] as? Number)?.toLong() ?: return null
        val maxCount = (rule.threshold["maxCount"] as? Number)?.toLong() ?: return null

        val since = event.occurredAt.minusSeconds(windowMinutes * 60)
        val count = recentTransferCountQueryPort.countByUserSince(event.senderId, since)

        return if (count > maxCount) {
            RiskRuleHit(
                txId = event.eventId,
                ruleCode = "RAPID_TRANSFER",
                severity = RuleSeverity.HIGH,
                weight = rule.weight.toInt(),
                reason = "최근 ${windowMinutes}분 내 송금 ${count}건 > 임계치 ${maxCount}건",
                occurredAt = Instant.now(),
            )
        } else {
            null
        }
    }

    /**
     * 최종 판정
     */
    private fun determineDecision(ruleHits: List<RiskRuleHit>): FinalDecisionType {
        val totalWeight = ruleHits.sumOf { it.weight }

        return when {
            ruleHits.isEmpty() -> FinalDecisionType.ALLOWED
            totalWeight >= 100 -> FinalDecisionType.BLOCKED
            totalWeight >= 50 -> FinalDecisionType.REVIEW
            else -> FinalDecisionType.ALLOWED
        }
    }
}
