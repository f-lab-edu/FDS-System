package io.github.hyungkishin.transentia.application.required

import io.github.hyungkishin.transentia.container.model.FraudeRule

interface FraudRuleRepository {
    fun save(fraudeRule: FraudeRule): Long

    fun findAllActive(): List<FraudeRule>
}
