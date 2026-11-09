package com.example.kotlintest.common.idempotency

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdempotencyKeyTest {

    @Test
    fun `accepts 1 to 255 visible ASCII characters`() {
        assertEquals("a", IdempotencyKey.validate("a"))
        val allVisible = (0x21..0x7E).map { it.toChar() }.joinToString("")
        assertEquals(allVisible, IdempotencyKey.validate(allVisible))
        val longest = "k".repeat(255)
        assertEquals(longest, IdempotencyKey.validate(longest))
    }

    @Test
    fun `missing or blank header is required`() {
        listOf(null, "", "   ", "\t").forEach { value ->
            assertFailsWith<IdempotencyKeyRequiredException>("'$value'") { IdempotencyKey.validate(value) }
        }
    }

    @Test
    fun `too long or non-visible characters are invalid`() {
        listOf("k".repeat(256), "with space", "tab\there", "café", "del\u007F").forEach { value ->
            assertFailsWith<InvalidIdempotencyKeyException>("'$value'") { IdempotencyKey.validate(value) }
        }
    }

    @Test
    fun `body hash is the SHA-256 hex digest of the raw bytes`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            IdempotencyKey.bodyHash(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            IdempotencyKey.bodyHash("abc".toByteArray()),
        )
    }
}
