package io.github.hyungkishin.transentia.infra.notification

import io.github.hyungkishin.transentia.application.required.AlertNotification
import io.github.hyungkishin.transentia.application.required.AlertNotificationPort
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Slack incoming webhook 으로 알림을 보내는 어댑터.
 *
 * - fds.alert.slack.webhook-url 가 설정된 경우에만 활성.
 * - CircuitBreaker(name=slack) — Slack outage 가 FDS 처리로 cascading 되는 것 방지.
 * - 전송 실패는 로그 + counter, 예외 전파 안 함 (알림 채널 장애 ≠ 송금 장애).
 */
@Component
@ConditionalOnProperty(name = ["fds.alert.slack.webhook-url"])
class SlackNotificationAdapter(
    @Value("\${fds.alert.slack.webhook-url}") private val webhookUrl: String,
    meterRegistry: MeterRegistry,
) : AlertNotificationPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: RestClient = RestClient.builder().build()

    private val sentCounter: Counter =
        Counter
            .builder("fds.alert.slack.sent")
            .description("Slack 알림 전송 성공 누적")
            .register(meterRegistry)
    private val failedCounter: Counter =
        Counter
            .builder("fds.alert.slack.failed")
            .description("Slack 알림 전송 실패 누적")
            .register(meterRegistry)

    @CircuitBreaker(name = "slack", fallbackMethod = "notifyFallback")
    override fun notify(notification: AlertNotification) {
        val color = colorFor(notification.severity)
        val body =
            mapOf(
                "attachments" to
                    listOf(
                        mapOf(
                            "color" to color,
                            "title" to "[FDS-${notification.severity}] 의심 패턴 탐지",
                            "text" to notification.reason,
                            "fields" to
                                listOf(
                                    field("Account ID", notification.accountId.toString(), true),
                                    field("Window", "${notification.windowMinutes}분", true),
                                    field("Transfer Count", notification.transferCount.toString(), true),
                                    field("Total Amount", "%,d원".format(notification.totalAmount), true),
                                    field("Trace ID", notification.traceId ?: "-", false),
                                ),
                        ),
                    ),
            )

        client
            .post()
            .uri(webhookUrl)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .retrieve()
            .toBodilessEntity()

        sentCounter.increment()
        log.info(
            "[slack-alert] accountId={} severity={} reason={}",
            notification.accountId,
            notification.severity,
            notification.reason,
        )
    }

    @Suppress("unused")
    fun notifyFallback(
        notification: AlertNotification,
        ex: Throwable,
    ) {
        failedCounter.increment()
        log.warn(
            "[slack-alert] fallback (CB open or send error). accountId={} cause={}",
            notification.accountId,
            ex.message,
        )
    }

    private fun field(
        title: String,
        value: String,
        short: Boolean,
    ) = mapOf("title" to title, "value" to value, "short" to short)

    private fun colorFor(severity: AlertNotification.Severity): String =
        when (severity) {
            AlertNotification.Severity.CRITICAL -> "#cc0000"
            AlertNotification.Severity.WARNING -> "#e8a317"
            AlertNotification.Severity.INFO -> "#36a64f"
        }
}
