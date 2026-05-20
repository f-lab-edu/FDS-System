package io.github.hyungkishin.transentia.infra.ai

import io.github.hyungkishin.transentia.application.required.AiScoreProvider
import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 통계 기반 anomaly score (ML PoC 2단계).
 *
 * 사용자별 송금액의 30일 롤링 평균/표준편차로 z-score 계산.
 * |z| 가 크면 평소 패턴에서 벗어난 거래로 보고 score ↑.
 *
 * 데이터 부족(N < 5) 시 0.0 반환 (cold start). HeuristicAiScoreAdapter
 * 와 달리 학습 데이터가 있는 사용자에게만 효과적.
 *
 * 한계 (E6 RETRO):
 *  - 30일 롤링 쿼리가 매 분석마다 실행 — 부하 측정 안 됨.
 *  - 표준편차 0 (모든 송금 동일 금액) 시 분기 처리.
 *  - 로그 스케일 vs 선형 스케일 결정 안 됨 (현재는 log).
 *  - 다변량 아님. 금액 한 변수만.
 */
@Component
@ConditionalOnProperty(name = ["fds.ai.score-provider"], havingValue = "statistical")
class StatisticalAiScoreAdapter(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) : AiScoreProvider {
    private val scoreDistribution: DistributionSummary =
        DistributionSummary
            .builder("fds.ai.score")
            .description("Statistical AI score 분포 (0~1) — z-score 기반")
            .baseUnit("score")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

    override fun score(event: TransferCompleteEvent): Double? {
        val stats = loadStatsSafe(event.senderId) ?: return 0.0
        if (stats.count < MIN_SAMPLES) return 0.0 // cold start

        val logAmount = ln(event.amount.coerceAtLeast(1L).toDouble())
        val mean = stats.meanLogAmount
        val std = max(stats.stdLogAmount, MIN_STD)
        val zAbs = abs(logAmount - mean) / std

        // |z| → score: sigmoid 로 0~1 압축
        val score = 1.0 / (1.0 + exp(-(zAbs - 2.0) * 1.5))
        val clamped = max(0.0, min(1.0, score))
        scoreDistribution.record(clamped)
        return clamped
    }

    private fun loadStatsSafe(senderId: Long): UserAmountStats? =
        try {
            jdbcTemplate.queryForObject(
                """
            SELECT
              COUNT(*) AS cnt,
              COALESCE(AVG(LN(GREATEST(amount, 1))), 0)    AS mean_log,
              COALESCE(STDDEV(LN(GREATEST(amount, 1))), 0) AS std_log
            FROM transactions
            WHERE sender_user_id = ?
              AND status = 'COMPLETED'
              AND created_at >= now() - INTERVAL '30 days'
            """,
                { rs, _ ->
                    UserAmountStats(
                        count = rs.getLong("cnt"),
                        meanLogAmount = rs.getDouble("mean_log"),
                        stdLogAmount = rs.getDouble("std_log"),
                    )
                },
                senderId,
            )
        } catch (e: Exception) {
            null
        }

    private data class UserAmountStats(
        val count: Long,
        val meanLogAmount: Double,
        val stdLogAmount: Double,
    )

    companion object {
        private const val MIN_SAMPLES = 5L
        private const val MIN_STD = 0.01
    }
}
