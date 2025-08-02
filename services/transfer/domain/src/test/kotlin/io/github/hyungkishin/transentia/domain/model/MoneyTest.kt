package io.github.hyungkishin.transentia.domain.model

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MoneyTest : StringSpec({

    "정상적인 소수 입력은 Money 객체로 변환된다" {
        val money = Money.fromDecimalString("123.45678901")
        money.toString() shouldBe "123.45678901"
    }

    "소수점이 없는 숫자는 정수 금액으로 인식된다" {
        val money = Money.fromDecimalString("100")
        money.toString() shouldBe "100"
    }

    "소수점이 8자리 초과일 경우 예외가 발생한다" {
        shouldThrowExactly<IllegalArgumentException> {
            Money.fromDecimalString("1.123456789")
        }
    }

    "음수 raw value를 생성하면 예외가 발생한다" {
        shouldThrowExactly<IllegalArgumentException> {
            Money.fromRawValue(-1)
        }
    }

    "금액 덧셈은 새로운 Money 인스턴스를 반환한다" {
        val a = Money.fromDecimalString("1.1")
        val b = Money.fromDecimalString("2.2")
        val result = a.add(b)
        result.toString() shouldBe "3.3"
    }

    "금액 뺄셈은 음수일 경우 예외가 발생한다" {
        val a = Money.fromDecimalString("1.0")
        val b = Money.fromDecimalString("2.0")
        shouldThrowExactly<IllegalArgumentException> {
            a.subtractOrThrow(b)
        }
    }

    "isZero는 정확히 0일 때 true를 반환한다" {
        val zero = Money.fromDecimalString("0.00000000")
        zero.isZero() shouldBe true
    }

    "isZero는 정확히 0이 아닌경우 false 를 반환한다" {
        val zero = Money.fromDecimalString("0.00000001")
        zero.isZero() shouldBe false
    }

    "isPositive는 양수일 때만 true를 반환한다" {
        val money = Money.fromDecimalString("0.00000001")
        money.isPositive() shouldBe true
    }

    "0원은 isPositive가 false를 반환한다" {
        val zero = Money.fromDecimalString("0.0")
        zero.isPositive() shouldBe false
    }

    "toString은 불필요한 소수점 0을 제거한다" {
        val money = Money.fromDecimalString("100.50000000")
        money.toString() shouldBe "100.5"
    }
})
