package io.github.hyungkishin.transentia.infra.notification

import io.github.hyungkishin.transentia.application.required.AlertNotification
import io.github.hyungkishin.transentia.application.required.AlertNotificationPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * Slack webhook 미설정 환경의 fallback.
 * SuspiciousPatternAlertConsumer 가 호출하지만 실제 push 는 안 함.
 */
@Component
@ConditionalOnMissingBean(value = [AlertNotificationPort::class], ignored = [NoOpAlertNotificationAdapter::class])
class NoOpAlertNotificationAdapter : AlertNotificationPort {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun notify(notification: AlertNotification) {
        log.debug(
            "[noop-alert] webhook 미설정. accountId={} reason={}",
            notification.accountId, notification.reason
        )
    }
}
