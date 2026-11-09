package com.example.kotlintest.acceptance

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for STORY-009: text length limits count Unicode code points.
 *
 * Black-box: HTTP API + plain SQL only. The database is shared between tests, so every test uses its own unique
 * emails and idempotency keys and only asserts on its own data. Request bodies are sent as UTF-8 bytes with the
 * characters written literally (no JSON `\u` escapes).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class Story009CodePointLengthAcceptanceTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val mapper = JsonMapper.shared()

    @Test
    fun `AC-1 accepts a name of 100 emoji and stores 100 code points`() {
        val name = EMOJI.repeat(100)
        assertEquals(100, codePoints(name))
        val email = uniqueEmail("ac1")

        val result = postUser(userJson(name, email), newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        assertEquals(name, json(result).path("name").asString())
        val row = jdbc.queryForMap("SELECT name, length(name) AS name_length FROM users WHERE email = ?", email)
        assertEquals(name, row["name"])
        assertEquals(100L, (row["name_length"] as Number).toLong())
    }

    @Test
    fun `AC-2 rejects a name of 101 emoji with 400 and no side effects`() {
        val name = EMOJI.repeat(101)
        assertEquals(101, codePoints(name))
        val email = uniqueEmail("ac2")
        val key = newKey()

        val result = postUser(userJson(name, email), key)

        assertProblem(result, 400)
        assertEquals(0L, count("SELECT count(*) FROM users WHERE email = ?", email))
        assertEquals(0L, count("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", key))
    }

    @Test
    fun `AC-3 accepts a name of 100 code points with 101 UTF-16 units and a name of a single emoji`() {
        val mixedName = "a".repeat(99) + EMOJI
        assertEquals(100, codePoints(mixedName))
        assertEquals(101, mixedName.length)
        val singleEmojiName = EMOJI
        assertEquals(1, codePoints(singleEmojiName))

        val mixed = postUser(userJson(mixedName, uniqueEmail("ac3a")), newKey())
        val single = postUser(userJson(singleEmojiName, uniqueEmail("ac3b")), newKey())

        assertEquals(201, mixed.status.value(), "(a) status; body: ${body(mixed)}")
        assertEquals(mixedName, json(mixed).path("name").asString())
        assertEquals(201, single.status.value(), "(b) status; body: ${body(single)}")
        assertEquals(singleEmojiName, json(single).path("name").asString())
    }

    @Test
    fun `AC-4 accepts an email of exactly 254 code points`() {
        val suffix = emailSuffix()
        val k = 254 - codePoints(suffix)
        assertTrue(k > 127, "k must be greater than 127, was: $k")
        val email = EMOJI.repeat(k) + suffix
        assertEquals(254, codePoints(email))
        assertTrue(email.length > 254, "email must have more than 254 UTF-16 units, had: ${email.length}")

        val result = postUser(userJson("Alice", email), newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        assertEquals(email, json(result).path("email").asString())
        val storedLength = jdbc.queryForObject(
            "SELECT length(email) FROM users WHERE email = ?",
            Long::class.javaObjectType,
            email,
        )
        assertEquals(254L, storedLength)
    }

    @Test
    fun `AC-5 rejects an email of 255 code points with 400`() {
        val suffix = emailSuffix()
        val k = 254 - codePoints(suffix)
        assertTrue(k > 127, "k must be greater than 127, was: $k")
        val email = EMOJI.repeat(k + 1) + suffix
        assertEquals(255, codePoints(email))

        val result = postUser(userJson("Alice", email), newKey())

        assertProblem(result, 400)
        assertEquals(0L, count("SELECT count(*) FROM users WHERE email = ?", email))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private fun postUser(body: String, key: String): EntityExchangeResult<ByteArray> =
        client.post().uri("/api/v1/users")
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_KEY, key)
            .body(body.toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()

    /** Builds the JSON body with the values written literally; the test values contain no characters to escape. */
    private fun userJson(name: String, email: String): String = """{"name":"$name","email":"$email"}"""

    private fun assertProblem(result: EntityExchangeResult<ByteArray>, status: Int) {
        assertEquals(status, result.status.value(), "status; body: ${body(result)}")
        val contentType = assertNotNull(result.responseHeaders.contentType, "Content-Type missing")
        assertTrue(
            MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType),
            "Content-Type must be application/problem+json, was: $contentType",
        )
        assertEquals(status, json(result).path("status").asInt())
    }

    private fun json(result: EntityExchangeResult<ByteArray>): JsonNode = mapper.readTree(result.responseBodyContent)

    private fun body(result: EntityExchangeResult<ByteArray>): String =
        result.responseBodyContent?.toString(Charsets.UTF_8).orEmpty()

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L

    private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** `@<uuid>.example.com`: lower-case ASCII only, so lower-casing the email doesn't change it. */
    private fun emailSuffix(): String = "@${UUID.randomUUID()}.example.com"

    private fun newKey(): String = UUID.randomUUID().toString()

    private fun uniqueEmail(prefix: String): String = "$prefix-${UUID.randomUUID()}@example.com"

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"

        /** U+1F600: one code point, two UTF-16 code units, four UTF-8 bytes. */
        val EMOJI = String(Character.toChars(0x1F600))
    }
}
