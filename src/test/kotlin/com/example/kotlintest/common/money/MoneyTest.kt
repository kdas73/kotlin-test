package com.example.kotlintest.common.money

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `parses values that floating point gets wrong exactly`() {
        assertEquals(29L, Money.parse("0.29"))
        assertEquals(57L, Money.parse("0.57"))
        assertEquals(115L, Money.parse("1.15"))
        assertEquals(1_000_000_000L, Money.parse("10000000.00"))
        assertEquals(0L, Money.parse("0.00"))
        assertEquals(5L, Money.parse("0.05"))
        assertEquals(5000L, Money.parse("50.00"))
    }

    @Test
    fun `formats minor units with exactly two fraction digits`() {
        assertEquals("0.29", Money.format(29))
        assertEquals("0.57", Money.format(57))
        assertEquals("1.15", Money.format(115))
        assertEquals("10000000.00", Money.format(1_000_000_000))
        assertEquals("0.00", Money.format(0))
        assertEquals("0.05", Money.format(5))
        assertEquals("50.00", Money.format(5000))
    }

    @Test
    fun `formats negative amounts with a leading minus`() {
        assertEquals("-15.00", Money.format(-1500))
        assertEquals("-0.05", Money.format(-5))
        assertEquals("-1.15", Money.format(-115))
        assertEquals("-92233720368547758.08", Money.format(Long.MIN_VALUE))
        assertEquals("92233720368547758.07", Money.format(Long.MAX_VALUE))
    }

    @Test
    fun `round-trips every amount from 0_00 to 20_00`() {
        for (minor in 0L..2000L) {
            assertEquals(minor, Money.parse(Money.format(minor)))
        }
    }

    @Test
    fun `rejects strings outside the request format`() {
        listOf("50", "50.5", "50.000", "50,00", "-1.00", "+1.00", " 50.00", "50.00 ", "", "05.00", "1e2", ".50", "50.")
            .forEach { text ->
                assertFailsWith<IllegalArgumentException>("'$text' must be rejected") { Money.parse(text) }
                assertFalse(Money.isValid(text), "'$text' must be invalid")
            }
    }

    @Test
    fun `rejects amounts that do not fit into a Long`() {
        assertFailsWith<ArithmeticException> { Money.parse("92233720368547758.08") }
        assertFailsWith<ArithmeticException> { Money.parse("99999999999999999999.00") }
        assertEquals(Long.MAX_VALUE, Money.parse("92233720368547758.07"))
        assertTrue(Money.isValid("92233720368547758.07"))
    }
}
