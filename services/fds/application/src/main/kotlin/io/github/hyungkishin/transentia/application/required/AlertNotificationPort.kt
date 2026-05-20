package io.github.hyungkishin.transentia.application.required

/**
 * 의심 패턴 알림을 운영자에게 push 하는 포트.
 * 어댑터는 Slack / WebSocket / 이메일 등 다양하게 교체 가능.
 *
 * 실패는 무시 정책 — 알림 전송 실패가 송금 처리에 영향 주면 안 된다.
 */
interface AlertNotificationPort {
    fun notify(notification: AlertNotification)
}

/**
 * 운영자에게 보낼 알림 메시지.
 */
data class AlertNotification(
    val accountId: Long,
    val transferCount: Int,
    val totalAmount: Long,
    val windowMinutes: Long,
    val reason: String,
    val traceId: String?,
    val severity: Severity = Severity.WARNING,
) {
    enum class Severity { INFO, WARNING, CRITICAL }
}
