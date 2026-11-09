package com.example.kotlintest.common.validation

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodePointLengthTest {

    private data class Sample(
        @field:CodePointLength(min = 1, max = 3)
        val text: String?,
    )

    private fun violations(text: String?) = validator.validate(Sample(text))

    @Test
    fun `counts a supplementary character as one code point`() {
        assertEquals(2, EMOJI.length)
        assertTrue(violations(EMOJI.repeat(3)).isEmpty())
        assertEquals(1, violations(EMOJI.repeat(4)).size)
    }

    @Test
    fun `counts mixed BMP and supplementary characters in code points`() {
        assertTrue(violations("ab$EMOJI").isEmpty())
        assertEquals(1, violations("abc$EMOJI").size)
    }

    @Test
    fun `enforces the minimum length`() {
        assertEquals(1, violations("").size)
        assertTrue(violations(EMOJI).isEmpty())
    }

    @Test
    fun `counts each code point of a grapheme cluster separately`() {
        // Thumbs up + skin tone modifier: one grapheme cluster, two code points.
        val thumbsUp = String(Character.toChars(0x1F44D)) + String(Character.toChars(0x1F3FD))
        assertTrue(violations(thumbsUp).isEmpty())
        assertEquals(1, violations(thumbsUp + thumbsUp).size)
    }

    @Test
    fun `leaves null to other constraints`() {
        assertTrue(violations(null).isEmpty())
    }

    @Test
    fun `reports the limits in the message`() {
        val violation = violations("abcd").single()
        assertEquals("length must be between 1 and 3 characters", violation.message)
        assertEquals("text", violation.propertyPath.toString())
    }

    companion object {
        /** U+1F600: one code point, two UTF-16 code units. */
        private val EMOJI = String(Character.toChars(0x1F600))

        private val factory = Validation.buildDefaultValidatorFactory()
        private val validator: Validator = factory.validator

        @JvmStatic
        @AfterAll
        fun closeFactory() = factory.close()
    }
}
