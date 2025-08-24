package io.github.hyungkishin.transentia.consumer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(
    scanBasePackages = [
        "io.github.hyungkishin.transentia"
    ]
)
class FdsConsumerApplication

fun main(args: Array<String>) {
    runApplication<FdsConsumerApplication>(*args)
}