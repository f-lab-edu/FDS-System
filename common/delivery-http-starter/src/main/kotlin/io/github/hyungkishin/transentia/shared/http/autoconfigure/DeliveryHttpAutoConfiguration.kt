package io.github.hyungkishin.transentia.shared.http.autoconfigure

import io.github.hyungkishin.transentia.shared.http.GlobalExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class DeliveryHttpAutoConfiguration {
    @Bean fun globalExceptionHandler() = GlobalExceptionHandler()
}