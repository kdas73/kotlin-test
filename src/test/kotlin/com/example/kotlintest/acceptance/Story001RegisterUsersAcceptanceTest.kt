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
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for STORY-001: register users with an account and idempotent POST.
 *
 * Black-box: HTTP API + plain SQL only. The database is shared between tests, so every test uses its own unique
 * emails and idempotency keys and only asserts on its own data.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class Story001RegisterUsersAcceptanceTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val mapper = JsonMapper.shared()

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 .. AC-3: registration, account, normalization
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-1 registers a user and returns 201 with Location and body`() {
        val email = uniqueEmail("alice")

        val result = postUser("""{"name":"Alice","email":"$email"}""", newKey())

        assertEquals(201, result.status.value())
        val body = json(result)
        val id = body.path("id")
        assertTrue(id.isIntegralNumber, "id must be an integer, was: $id")
        assertEquals("Alice", body.path("name").asString())
        assertEquals(email, body.path("email").asString())
        assertIsoUtcTimestamp(body.path("createdAt"))

        val location = assertNotNull(result.responseHeaders.getFirst("Location"), "Location header missing")
        assertTrue(
            URI.create(location).path.endsWith("/api/v1/users/${id.asLong()}"),
            "Location path must end with /api/v1/users/${id.asLong()}, was: $location",
        )

        assertEquals(1L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-2 registration creates exactly one USER account with zero balance`() {
        val email = uniqueEmail("alice")
        val created = postUser("""{"name":"Alice","email":"$email"}""", newKey())
        assertEquals(201, created.status.value())
        val userId = json(created).path("id").asLong()

        val accountRows = jdbc.queryForList(
            "SELECT id, kind, balance, typeof(balance) AS balance_type FROM accounts WHERE user_id = ?",
            userId,
        )
        assertEquals(1, accountRows.size, "exactly one account row for user $userId")
        val row = accountRows.single()
        assertEquals("USER", row["kind"])
        assertEquals(0L, (row["balance"] as Number).toLong())
        assertEquals("integer", row["balance_type"])
        val accountId = (row["id"] as Number).toLong()

        val account = client.get().uri("/api/v1/users/{id}/account", userId)
            .exchange()
            .expectBody()
            .returnResult()
        assertEquals(200, account.status.value())
        val accountBody = json(account)
        assertEquals(accountId, accountBody.path("id").asLong())
        assertTrue(accountBody.path("id").isIntegralNumber)
        assertEquals(userId, accountBody.path("userId").asLong())
        assertTrue(accountBody.path("userId").isIntegralNumber)
        val balance = accountBody.path("balance")
        assertTrue(balance.isString, "balance must be a JSON string, was: $balance")
        assertEquals("0.00", balance.asString())
    }

    @Test
    fun `AC-3 trims name and email and stores the email in lower case`() {
        val unique = UUID.randomUUID().toString()

        val result = postUser("""{"name":"  Bob  ","email":"  Bob-$unique@Example.COM "}""", newKey())

        assertEquals(201, result.status.value())
        val body = json(result)
        val expectedEmail = "bob-$unique@example.com"
        assertEquals("Bob", body.path("name").asString())
        assertEquals(expectedEmail, body.path("email").asString())

        val row = jdbc.queryForMap("SELECT name, email FROM users WHERE id = ?", body.path("id").asLong())
        assertEquals("Bob", row["name"])
        assertEquals(expectedEmail, row["email"])
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4: validation errors have no side effects
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-4 (a) rejects a request without name with 400 and no side effects`() {
        assertRejectedWithoutSideEffects("""{"email":"${uniqueEmail("noname")}"}""")
    }

    @Test
    fun `AC-4 (b) rejects a blank name with 400 and no side effects`() {
        assertRejectedWithoutSideEffects("""{"name":"   ","email":"${uniqueEmail("blank")}"}""")
    }

    @Test
    fun `AC-4 (c) rejects a name of 101 characters with 400 and no side effects`() {
        val name = "N".repeat(101)
        assertRejectedWithoutSideEffects("""{"name":"$name","email":"${uniqueEmail("longname")}"}""")
    }

    @Test
    fun `AC-4 (d) rejects a request without email with 400 and no side effects`() {
        assertRejectedWithoutSideEffects("""{"name":"Alice"}""")
    }

    @Test
    fun `AC-4 (e) rejects an invalid email with 400 and no side effects`() {
        assertRejectedWithoutSideEffects("""{"name":"Alice","email":"not-an-email"}""")
    }

    @Test
    fun `AC-4 (f) rejects an email of 255 characters with 400 and no side effects`() {
        val domain = "@example.com"
        val prefix = "e-${UUID.randomUUID()}-"
        val email = prefix + "x".repeat(255 - prefix.length - domain.length) + domain
        assertEquals(255, email.length)
        assertRejectedWithoutSideEffects("""{"name":"Alice","email":"$email"}""")
    }

    @Test
    fun `AC-4 (g) rejects malformed JSON with 400 and no side effects`() {
        assertRejectedWithoutSideEffects("""{"name":"Alice","email":""")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-5 .. AC-8: uniqueness and reads
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-5 rejects an email that differs only in letter case with 409`() {
        val unique = UUID.randomUUID().toString()
        val email = "carol-$unique@example.com"
        assertEquals(201, postUser("""{"name":"Carol","email":"$email"}""", newKey()).status.value())

        val result = postUser("""{"name":"Carol","email":"CAROL-$unique@example.com"}""", newKey())

        assertProblem(result, 409, "Email already in use")
        assertEquals(1L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-6 reads a user by id and returns 404 problem detail for an unknown id`() {
        val created = postUser("""{"name":"Dave","email":"${uniqueEmail("dave")}"}""", newKey())
        assertEquals(201, created.status.value())
        val createdBody = json(created)

        val read = client.get().uri("/api/v1/users/{id}", createdBody.path("id").asLong())
            .exchange()
            .expectBody()
            .returnResult()

        assertEquals(200, read.status.value())
        assertEquals(createdBody, json(read))

        val unknown = client.get().uri("/api/v1/users/{id}", UNKNOWN_ID)
            .exchange()
            .expectBody()
            .returnResult()
        assertProblem(unknown, 404)
    }

    @Test
    fun `AC-7 returns 404 problem detail for the account of an unknown user`() {
        val result = client.get().uri("/api/v1/users/{id}/account", UNKNOWN_ID)
            .exchange()
            .expectBody()
            .returnResult()

        assertProblem(result, 404)
    }

    @Test
    fun `AC-8 returns the requested page sorted by id ascending`() {
        repeat(3) { i ->
            assertEquals(201, postUser("""{"name":"Page $i","email":"${uniqueEmail("page$i")}"}""", newKey()).status.value())
        }

        val result = client.get().uri("/api/v1/users?page=0&size=2&sort=id,asc")
            .exchange()
            .expectBody()
            .returnResult()

        assertEquals(200, result.status.value())
        val totalElements = jdbc.queryForObject("SELECT count(*) FROM users", Long::class.javaObjectType) ?: 0L
        val firstTwoIds = jdbc.queryForList("SELECT id FROM users ORDER BY id ASC LIMIT 2", Long::class.javaObjectType)
        val body = json(result)
        val content = body.path("content")
        assertTrue(content.isArray, "content must be an array")
        assertEquals(firstTwoIds, (0 until content.size()).map { content.path(it).path("id").asLong() })
        val page = body.path("page")
        assertEquals(2, page.path("size").asInt())
        assertEquals(0, page.path("number").asInt())
        assertEquals(totalElements, page.path("totalElements").asLong())
        assertEquals((totalElements + 1) / 2, page.path("totalPages").asLong())
    }

    @Test
    fun `AC-8 uses page size 20 by default`() {
        val result = client.get().uri("/api/v1/users")
            .exchange()
            .expectBody()
            .returnResult()

        assertEquals(200, result.status.value())
        assertEquals(20, json(result).path("page").path("size").asInt())
    }

    @Test
    fun `AC-8 caps the page size at 100`() {
        val result = client.get().uri("/api/v1/users?size=500")
            .exchange()
            .expectBody()
            .returnResult()

        assertEquals(200, result.status.value())
        assertEquals(100, json(result).path("page").path("size").asInt())
    }

    @Test
    fun `AC-8 rejects an unknown sort property with 400`() {
        client.get().uri("/api/v1/users?sort=unknownField,asc")
            .exchange()
            .expectStatus().isBadRequest
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-9 .. AC-14: Idempotency-Key
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-9 (a) rejects a POST without Idempotency-Key header with 400`() {
        val email = uniqueEmail("nokey")

        val result = postUser("""{"name":"Alice","email":"$email"}""", key = null)

        assertProblem(result, 400, "Idempotency-Key header required")
        assertEquals(0L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-9 (b) rejects a POST with a blank Idempotency-Key header with 400`() {
        val email = uniqueEmail("blankkey")

        val result = postUser("""{"name":"Alice","email":"$email"}""", key = "   ")

        assertProblem(result, 400, "Idempotency-Key header required")
        assertEquals(0L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-10 (a) rejects an Idempotency-Key of 256 characters with 400`() {
        val email = uniqueEmail("longkey")
        val key = UUID.randomUUID().toString().padEnd(256, 'k')
        assertEquals(256, key.length)

        val result = postUser("""{"name":"Alice","email":"$email"}""", key)

        assertProblem(result, 400, "Invalid Idempotency-Key header")
        assertEquals(0L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-10 (b) rejects an Idempotency-Key containing a space with 400`() {
        val email = uniqueEmail("spacekey")

        val result = postUser("""{"name":"Alice","email":"$email"}""", "key with-space-${UUID.randomUUID()}")

        assertProblem(result, 400, "Invalid Idempotency-Key header")
        assertEquals(0L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-11 replays the stored response for the same key and the same request`() {
        val key = newKey()
        val email = uniqueEmail("replay")
        val request = """{"name":"Alice","email":"$email"}"""

        val first = postUser(request, key)
        assertEquals(201, first.status.value())
        assertNull(first.responseHeaders.getFirst(REPLAYED), "first response must not be marked as replayed")
        val firstLocation = assertNotNull(first.responseHeaders.getFirst("Location"))

        val second = postUser(request, key)

        assertEquals(201, second.status.value())
        assertEquals("true", second.responseHeaders.getFirst(REPLAYED))
        assertEquals(firstLocation, second.responseHeaders.getFirst("Location"))
        assertEquals(json(first), json(second))

        val userId = json(first).path("id").asLong()
        assertEquals(1L, countUsersWithEmail(email))
        assertEquals(1L, count("SELECT count(*) FROM accounts WHERE user_id = ?", userId))
        val keyRows = jdbc.queryForList(
            "SELECT request_method, request_path, response_status FROM idempotency_keys WHERE idempotency_key = ?",
            key,
        )
        assertEquals(1, keyRows.size)
        val keyRow = keyRows.single()
        assertEquals("POST", keyRow["request_method"])
        assertEquals("/api/v1/users", keyRow["request_path"])
        assertEquals(201L, (keyRow["response_status"] as Number).toLong())
    }

    @Test
    fun `AC-12 rejects a reused key with a different request with 422`() {
        val key = newKey()
        val emailA = uniqueEmail("a")
        val emailB = uniqueEmail("b")
        assertEquals(201, postUser("""{"name":"Alice","email":"$emailA"}""", key).status.value())

        val result = postUser("""{"name":"Alice","email":"$emailB"}""", key)

        assertProblem(result, 422, "Idempotency key reused")
        assertEquals(0L, countUsersWithEmail(emailB))
    }

    @Test
    fun `AC-13 a key whose request failed with 400 can be reused for a valid request`() {
        val key = newKey()
        val email = uniqueEmail("retry400")
        val failed = postUser("""{"name":"   ","email":"$email"}""", key)
        assertEquals(400, failed.status.value())

        val result = postUser("""{"name":"Alice","email":"$email"}""", key)

        assertEquals(201, result.status.value())
        assertNull(result.responseHeaders.getFirst(REPLAYED), "a first successful response must not be replayed")
        assertEquals(1L, countUsersWithEmail(email))
    }

    @Test
    fun `AC-13 a key whose request failed with 409 can be reused with a different email`() {
        val takenEmail = uniqueEmail("taken")
        assertEquals(201, postUser("""{"name":"Alice","email":"$takenEmail"}""", newKey()).status.value())
        val key = newKey()
        val conflict = postUser("""{"name":"Alice","email":"$takenEmail"}""", key)
        assertProblem(conflict, 409, "Email already in use")

        val freeEmail = uniqueEmail("free")
        val result = postUser("""{"name":"Alice","email":"$freeEmail"}""", key)

        assertEquals(201, result.status.value())
        assertNull(result.responseHeaders.getFirst(REPLAYED), "a first successful response must not be replayed")
        assertEquals(1L, countUsersWithEmail(freeEmail))
    }

    @Test
    fun `AC-14 concurrent identical requests with the same key perform the operation at most once`() {
        val key = newKey()
        val email = uniqueEmail("concurrent")
        val request = """{"name":"Alice","email":"$email"}"""
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<EntityExchangeResult<ByteArray>>()
        val errors = ConcurrentLinkedQueue<Throwable>()

        val threads = (1..CONCURRENT_REQUESTS).map {
            Thread.ofVirtual().start {
                try {
                    start.await()
                    results.add(postUser(request, key))
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        start.countDown()
        threads.forEach { assertTrue(it.join(Duration.ofSeconds(60)), "request thread did not finish in time") }

        assertTrue(errors.isEmpty(), "requests failed on the client side: $errors")
        assertEquals(CONCURRENT_REQUESTS, results.size)
        val created = results.filter { it.status.value() == 201 }
        val others = results.filter { it.status.value() != 201 }
        others.forEach { assertProblem(it, 409, "Request in progress") }
        assertTrue(created.isNotEmpty(), "at least one response must be 201")
        val firstBody = json(created.first())
        created.forEach { assertEquals(firstBody, json(it)) }

        assertEquals(1L, countUsersWithEmail(email))
        val userId = assertNotNull(
            jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long::class.javaObjectType, email),
        )
        assertEquals(1L, count("SELECT count(*) FROM accounts WHERE user_id = ?", userId))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private fun postUser(body: String, key: String?): EntityExchangeResult<ByteArray> =
        client.post().uri("/api/v1/users")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> if (key != null) headers.set(IDEMPOTENCY_KEY, key) }
            .body(body)
            .exchange()
            .expectBody()
            .returnResult()

    private fun assertRejectedWithoutSideEffects(body: String) {
        val key = newKey()
        val usersBefore = count("SELECT count(*) FROM users")
        val accountsBefore = count("SELECT count(*) FROM accounts")

        val result = postUser(body, key)

        assertProblem(result, 400)
        assertEquals(usersBefore, count("SELECT count(*) FROM users"), "users row count changed")
        assertEquals(accountsBefore, count("SELECT count(*) FROM accounts"), "accounts row count changed")
        assertEquals(0L, count("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", key))
    }

    private fun assertProblem(result: EntityExchangeResult<ByteArray>, status: Int, title: String? = null) {
        assertEquals(status, result.status.value(), "status; body: ${String(result.responseBodyContent)}")
        val contentType = assertNotNull(result.responseHeaders.contentType, "Content-Type missing")
        assertTrue(
            MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType),
            "Content-Type must be application/problem+json, was: $contentType",
        )
        val body = json(result)
        assertEquals(status, body.path("status").asInt())
        if (title != null) {
            assertEquals(title, body.path("title").asString())
        }
    }

    private fun assertIsoUtcTimestamp(node: JsonNode) {
        assertTrue(node.isString, "timestamp must be a JSON string, was: $node")
        val text = node.asString()
        assertTrue(ISO_UTC.matches(text), "timestamp must be ISO-8601 UTC ending in Z, was: $text")
        Instant.parse(text)
    }

    private fun json(result: EntityExchangeResult<ByteArray>): JsonNode = mapper.readTree(result.responseBodyContent)

    private fun countUsersWithEmail(email: String): Long = count("SELECT count(*) FROM users WHERE email = ?", email)

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L

    private fun newKey(): String = UUID.randomUUID().toString()

    private fun uniqueEmail(prefix: String): String = "$prefix-${UUID.randomUUID()}@example.com"

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val REPLAYED = "Idempotent-Replayed"
        const val CONCURRENT_REQUESTS = 10
        const val UNKNOWN_ID = Long.MAX_VALUE
        val ISO_UTC = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")
    }
}
