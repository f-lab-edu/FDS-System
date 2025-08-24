package io.github.hyungkishin.transentia.consumer.model

import io.kotest.core.spec.style.StringSpec
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import kotlin.system.measureNanoTime

// BigDecimal 연산과, Long 타입의 연산을 비교하는 testCode 용도
// CI 환경 이나, IDE 테스트 실행 결과가 느려지거나, 타임아웃에 걸릴 수 있어 Disabled 처리
@Disabled
class MoneyPerformanceTest : StringSpec({
    val N = 10_000_000

    "BigDecimal 성능 (연산 누적값 확인용)" {

        val bigDecimalOne = BigDecimal("1.0")

        var sumDecimal: BigDecimal
        val decimalTime = measureNanoTime {
            var sum = BigDecimal.ZERO
            repeat(N) {
                sum = sum.add(bigDecimalOne)
            }
            sumDecimal = sum
        }

        println("BigDecimal: ${decimalTime / 1_000_000} ms, result = $sumDecimal")
    }

    "Money(Long 기반) 성능 (연산 누적값 확인용)" {
        val moneyOne = Money.fromDecimalString("1.0")
        var sumMoney: Money

        val moneyTime = measureNanoTime {
            var sum = Money.fromDecimalString("0.0")
            repeat(N) {
                sum = sum.add(moneyOne)
            }
            sumMoney = sum
        }
        println("Money (Long): ${moneyTime / 1_000_000} ms, result = $sumMoney")
    }
})