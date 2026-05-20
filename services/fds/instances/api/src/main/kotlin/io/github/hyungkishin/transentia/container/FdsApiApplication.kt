package io.github.hyungkishin.transentia.container

import io.github.hyungkishin.transentia.infra.config.FdsInfraJpaConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(
    scanBasePackages = ["io.github.hyungkishin.transentia"],
)
@Import(FdsInfraJpaConfig::class)
class FdsApiApplication

fun main(args: Array<String>) {
    runApplication<FdsApiApplication>(*args)
}
