package io.github.hyungkishin.transentia.api

import io.github.hyungkishin.transentia.infra.config.TransferInfraJpaConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(scanBasePackages = ["io.github.hyungkishin.transentia"])
@Import(TransferInfraJpaConfig::class)
class TransferApiApplication

fun main(args: Array<String>) {
    runApplication<TransferApiApplication>(*args)
}
