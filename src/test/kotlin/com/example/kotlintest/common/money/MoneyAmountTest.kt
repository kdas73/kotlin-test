package com.example.kotlintest.common.money

import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Implementer's unit tests for [MoneyAmount] (STORY-003, not locked). */
class MoneyAmountTest {

    private class Target(@field:MoneyAmount(min = "0.01", max = "10000000.00") val price: String?)

    private val validator = Validation.buildDefaultValidatorFactory().use { it.validator }

    private fun isValid(price: String?) = validator.validate(Target(price)).isEmpty()

    @Test
    fun `null is valid`() {
        assertTrue(isValid(null))
    }

    @Test
    fun `amounts within the range are valid, including both bounds`() {
        listOf("0.01", "0.29", "15.00", "9999999.99", "10000000.00").forEach { price ->
            assertTrue(isValid(price), "'$price' must be valid")
        }
    }

    @Test
    fun `amounts outside the range are invalid`() {
        listOf("0.00", "10000000.01", "99999999999999999999.00").forEach { price ->
            assertFalse(isValid(price), "'$price' must be invalid")
        }
    }

    @Test
    fun `badly formatted amounts are invalid`() {
        listOf("", "15", "15.5", "15.000", "15,00", "-1.00", "+1.00", "01.00", " 1.00", "1.00 ", "abc").forEach { price ->
            assertFalse(isValid(price), "'$price' must be invalid")
        }
    }
}
