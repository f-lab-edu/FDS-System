package io.github.hyungkishin.fdsystem

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FdsSystemApplication

fun main(args: Array<String>) {
    runApplication<FdsSystemApplication>(*args)
}