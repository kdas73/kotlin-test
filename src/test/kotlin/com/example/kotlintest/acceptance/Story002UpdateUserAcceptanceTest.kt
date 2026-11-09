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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for STORY-002: update user name and email with `PATCH /api/v1/users/{id}`.
 *
 * Black-box: HTTP API + plain SQL only. The database is shared between tests, so every test inserts its own users
 * with unique emails and only asserts on its own data. No request sends an `Idempotency-Key` header. Request bodies
 * are sent as UTF-8 bytes with the characters written literally (no JSON `\u` escapes).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class Story002UpdateUserAcceptanceTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val mapper = JsonMapper.shared()

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 .. AC-4: successful updates
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-1 trims and updates the name without an Idempotency-Key and keeps the email`() {
        val email = uniqueEmail("alice")
        val id = insertUser("Alice", email)

        val result = patchUser(id, """{"name":"  Alice Smith "}""")

        assertEquals(200, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        assertEquals("Alice Smith", response.path("name").asString())
        assertEquals(email, response.path("email").asString())
        val row = userRow(id)
        assertEquals("Alice Smith", row["name"])
        assertEquals(email, row["email"])
    }

    @Test
    fun `AC-2 trims and lower-cases a new email and keeps the name`() {
        val id = insertUser("Bob", uniqueEmail("bob"))
        val unique = UUID.randomUUID().toString()

        val result = patchUser(id, """{"email":" New-$unique@Example.com "}""")

        assertEquals(200, result.status.value(), "status; body: ${body(result)}")
        val expectedEmail = "new-$unique@example.com"
        val response = json(result)
        assertEquals(expectedEmail, response.path("email").asString())
        assertEquals("Bob", response.path("name").asString())
        val row = userRow(id)
        assertEquals(expectedEmail, row["email"])
        assertEquals("Bob", row["name"])
    }

    @Test
    fun `AC-3 updates name and email together and keeps id and createdAt`() {
        val id = insertUser("Carol", uniqueEmail("carol"))
        val newEmail = uniqueEmail("carol-new")

        val result = patchUser(id, """{"name":"Carol King","email":"$newEmail"}""")

        assertEquals(200, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        assertEquals("Carol King", response.path("name").asString())
        assertEquals(newEmail, response.path("email").asString())
        assertTrue(response.path("id").isIntegralNumber, "id must be an integer, was: ${response.path("id")}")
        assertEquals(id, response.path("id").asLong())
        val createdAt = response.path("createdAt")
        assertTrue(createdAt.isString, "createdAt must be a JSON string, was: $createdAt")
        assertEquals(Instant.parse(CREATED_AT), Instant.parse(createdAt.asString()))
        val row = userRow(id)
        assertEquals("Carol King", row["name"])
        assertEquals(newEmail, row["email"])
        assertEquals(CREATED_AT, row["created_at"])
    }

    @Test
    fun `AC-4 repeating the same PATCH returns the same 200 body and changes nothing`() {
        val id = insertUser("Eve", uniqueEmail("eve"))
        val unique = UUID.randomUUID().toString()
        val request = """{"name":"  Eve Adams ","email":" Eve-$unique@Example.com "}"""
        val keysBefore = count("SELECT count(*) FROM idempotency_keys")

        val first = patchUser(id, request)
        assertEquals(200, first.status.value(), "first status; body: ${body(first)}")
        val rowAfterFirst = userRow(id)

        val second = patchUser(id, request)

        assertEquals(200, second.status.value(), "second status; body: ${body(second)}")
        assertEquals(json(first), json(second))
        assertEquals(rowAfterFirst, userRow(id))
        assertEquals(keysBefore, count("SELECT count(*) FROM idempotency_keys"), "idempotency_keys row count changed")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-5: validation errors leave the user unchanged
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-5 (a) rejects an empty object with 400 and leaves the user unchanged`() {
        assertRejectedAndUnchanged("""{}""")
    }

    @Test
    fun `AC-5 (b) rejects null name and null email with 400 and leaves the user unchanged`() {
        assertRejectedAndUnchanged("""{"name":null,"email":null}""")
    }

    @Test
    fun `AC-5 (c) rejects a blank name with 400 and leaves the user unchanged`() {
        assertRejectedAndUnchanged("""{"name":"   "}""")
    }

    @Test
    fun `AC-5 (d) rejects a name of 101 characters with 400 and leaves the user unchanged`() {
        val name = "N".repeat(101)
        assertRejectedAndUnchanged("""{"name":"$name"}""")
    }

    @Test
    fun `AC-5 (e) rejects an invalid email with 400 and leaves the user unchanged`() {
        assertRejectedAndUnchanged("""{"email":"not-an-email"}""")
    }

    @Test
    fun `AC-5 (f) rejects malformed JSON with 400 and leaves the user unchanged`() {
        assertRejectedAndUnchanged("""{"name":"Alice"""")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-6 .. AC-8: email conflicts and unknown users
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-6 rejects another user's email in upper case with 409 and leaves the user unchanged`() {
        val emailA = uniqueEmail("usera")
        insertUser("User A", emailA)
        val idB = insertUser("User B", uniqueEmail("userb"))
        val rowBefore = userRow(idB)

        val result = patchUser(idB, """{"email":"${emailA.uppercase()}"}""")

        assertProblem(result, 409, "Email already in use")
        assertEquals(rowBefore, userRow(idB))
    }

    @Test
    fun `AC-7 accepts the user's own email in a different letter case`() {
        val unique = UUID.randomUUID().toString()
        val email = "dave-$unique@example.com"
        val id = insertUser("Dave", email)

        val result = patchUser(id, """{"email":"DAVE-${unique.uppercase()}@EXAMPLE.COM"}""")

        assertEquals(200, result.status.value(), "status; body: ${body(result)}")
        assertEquals(email, json(result).path("email").asString())
        assertEquals(email, userRow(id)["email"])
    }

    @Test
    fun `AC-8 returns 404 problem detail for an unknown user`() {
        assertEquals(0L, count("SELECT count(*) FROM users WHERE id = ?", UNKNOWN_ID))

        val result = patchUser(UNKNOWN_ID, """{"name":"Alice"}""")

        assertProblem(result, 404)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-9: name length counts Unicode code points
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-9 accepts a name of 100 emoji and rejects 101 emoji leaving the row unchanged`() {
        val id = insertUser("Frank", uniqueEmail("frank"))
        val name100 = EMOJI.repeat(100)
        assertEquals(100, codePoints(name100))
        val name101 = EMOJI.repeat(101)
        assertEquals(101, codePoints(name101))

        val accepted = patchUser(id, """{"name":"$name100"}""")

        assertEquals(200, accepted.status.value(), "status; body: ${body(accepted)}")
        assertEquals(name100, json(accepted).path("name").asString())
        assertEquals(name100, userRow(id)["name"])

        val rejected = patchUser(id, """{"name":"$name101"}""")

        assertProblem(rejected, 400)
        assertEquals(name100, userRow(id)["name"])
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private fun patchUser(id: Long, body: String): EntityExchangeResult<ByteArray> =
        client.patch().uri("/api/v1/users/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body.toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()

    private fun assertRejectedAndUnchanged(body: String) {
        val id = insertUser("Grace", uniqueEmail("grace"))
        val rowBefore = userRow(id)

        val result = patchUser(id, body)

        assertProblem(result, 400)
        assertEquals(rowBefore, userRow(id), "users row changed")
    }

    private fun assertProblem(result: EntityExchangeResult<ByteArray>, status: Int, title: String? = null) {
        assertEquals(status, result.status.value(), "status; body: ${body(result)}")
        val contentType = assertNotNull(result.responseHeaders.contentType, "Content-Type missing")
        assertTrue(
            MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType),
            "Content-Type must be application/problem+json, was: $contentType",
        )
        val problem = json(result)
        assertEquals(status, problem.path("status").asInt())
        if (title != null) {
            assertEquals(title, problem.path("title").asString())
        }
    }

    /** Inserts a user directly with SQL and returns its ID. `email` must already be trimmed and lower case. */
    private fun insertUser(name: String, email: String): Long {
        jdbc.update("INSERT INTO users (name, email, created_at) VALUES (?, ?, ?)", name, email, CREATED_AT)
        return assertNotNull(
            jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long::class.javaObjectType, email),
        )
    }

    private fun userRow(id: Long): Map<String, Any?> =
        jdbc.queryForMap("SELECT id, name, email, created_at FROM users WHERE id = ?", id)

    private fun json(result: EntityExchangeResult<ByteArray>): JsonNode = mapper.readTree(result.responseBodyContent)

    private fun body(result: EntityExchangeResult<ByteArray>): String =
        result.responseBodyContent.toString(Charsets.UTF_8)

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L

    private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    private fun uniqueEmail(prefix: String): String = "$prefix-${UUID.randomUUID()}@example.com"

    private companion object {
        const val CREATED_AT = "2026-11-08T12:00:00.123Z"
        const val UNKNOWN_ID = Long.MAX_VALUE

        /** U+1F600: one code point, two UTF-16 code units, four UTF-8 bytes. */
        val EMOJI = String(Character.toChars(0x1F600))
    }
}
