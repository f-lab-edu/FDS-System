package io.github.hyungkishin.transentia.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["io.github.hyungkishin.transentia"])
class TransferApiApplication

fun main(args: Array<String>) {
    runApplication<TransferApiApplication>(*args)
}
