package io.github.hyungkishin.transentia.infra.ai

import io.github.hyungkishin.transentia.application.required.AiScoreProvider
import io.github.hyungkishin.transentia.application.required.RecentTransferCountQueryPort
import io.github.hyungkishin.transentia.container.event.TransferCompleteEvent
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 휴리스틱 기반 anomaly score (ML PoC 1단계).
 *
 * 학습 데이터가 없는 상태에서 룰 4개를 가중합한 뒤 sigmoid 로 0~1 정규화.
 * 결과는 RiskLog.aiScore 에 들어가고, decisionWeight 와는 분리.
 *
 * 4개 휴리스틱:
 *  - 금액: log10(amount) 가 6 이상이면 +0.4 (100만원 이상 가산)
 *  - 시간: 02~05 KST 새벽 송금이면 +0.2
 *  - 속도: 최근 5분 N건 (N>=3 부터 선형) → 최대 +0.3
 *  - 신선도: NOTE 아직 미구현 (계좌 생성 이후 시간) — 0
 *
 * 가중치는 단순 합산 후 sigmoid 통해 0~1 로 압축.
 *
 * 한계 (E6 RETRO 에 명시):
 *  - 가중치 자체가 사람 직관. 학습 없음.
 *  - 사용자 패턴 무시 (전체 평균 사용자 가정).
 *  - false positive 측정 안 됨.
 */
@Component
@ConditionalOnProperty(name = ["fds.ai.score-provider"], havingValue = "heuristic", matchIfMissing = true)
class HeuristicAiScoreAdapter(
    private val recentTransferCountQueryPort: RecentTransferCountQueryPort,
    meterRegistry: MeterRegistry,
) : AiScoreProvider {

    private val scoreDistribution: DistributionSummary = DistributionSummary.builder("fds.ai.score")
        .description("Heuristic AI score 분포 (0~1)")
        .baseUnit("score")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry)

    override fun score(event: TransferCompleteEvent): Double? {
        val raw = (
            amountFeature(event.amount) +
                hourFeature(event) +
                velocityFeature(event)
            )
        // sigmoid 로 0~1 압축
        val score = 1.0 / (1.0 + exp(-(raw - 0.5) * 4.0))
        val clamped = max(0.0, min(1.0, score))
        scoreDistribution.record(clamped)
        return clamped
    }

    private fun amountFeature(amount: Long): Double {
        if (amount <= 0) return 0.0
        val log10Amount = ln(amount.toDouble()) / ln(10.0)
        return when {
            log10Amount >= 7.0 -> 0.4 // 1천만원+
            log10Amount >= 6.0 -> 0.25 // 100만원+
            log10Amount >= 5.0 -> 0.1 // 10만원+
            else -> 0.0
        }
    }

    private fun hourFeature(event: TransferCompleteEvent): Double {
        val hour = event.occurredAt.atZone(KST).hour
        return if (hour in NIGHT_RANGE) 0.2 else 0.0
    }

    private fun velocityFeature(event: TransferCompleteEvent): Double {
        val since = event.occurredAt.minus(5, ChronoUnit.MINUTES)
        val count = recentTransferCountQueryPort.countByUserSince(event.senderId, since)
        return when {
            count >= 5 -> 0.3
            count >= 3 -> 0.15
            else -> 0.0
        }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val NIGHT_RANGE = 2..5 // 02:00 ~ 05:59 KST
    }
}
