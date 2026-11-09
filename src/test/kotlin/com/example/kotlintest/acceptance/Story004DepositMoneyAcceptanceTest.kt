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
import kotlin.test.assertTrue

/**
 * Acceptance tests for STORY-004: deposit money with a double-entry ledger
 * (`POST /api/v1/users/{id}/account/deposits`, `GET /api/v1/transactions/{id}`).
 *
 * Black-box: HTTP API + plain SQL only. The database is shared between tests and test classes, so nothing is deleted:
 * every test registers its own users (through the STORY-001 API) and uses its own idempotency keys. Balances of the
 * system `FUNDING` account are checked as differences (before/after). API money values are strings in major units,
 * database values are integers in minor units (`"50.00"` <-> `5000`).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class Story004DepositMoneyAcceptanceTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val mapper = JsonMapper.shared()

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1: system accounts
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-1 migrations create exactly one FUNDING and one REVENUE account without a user`() {
        assertEquals(1L, count("SELECT count(*) FROM accounts WHERE kind = 'FUNDING'"), "FUNDING accounts")
        assertEquals(1L, count("SELECT count(*) FROM accounts WHERE kind = 'REVENUE'"), "REVENUE accounts")
        assertEquals(
            0L,
            count("SELECT count(*) FROM accounts WHERE kind IN ('FUNDING', 'REVENUE') AND user_id IS NOT NULL"),
            "system accounts must have user_id IS NULL",
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-2 .. AC-4: deposit, balance, reading a transaction
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-2 deposits 50_00 and returns 201 with Location, the transaction and its ledger rows`() {
        val user = newUser()
        val funding = fundingAccount()

        val result = deposit(user.id, """{"amount":"50.00"}""", newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        val idNode = response.path("id")
        assertTrue(idNode.isIntegralNumber, "id must be an integer, was: $idNode")
        val txId = idNode.asLong()
        val location = assertNotNull(result.responseHeaders.getFirst("Location"), "Location header missing")
        assertTrue(
            URI.create(location).path.endsWith("/api/v1/transactions/$txId"),
            "Location path must end with /api/v1/transactions/$txId, was: $location",
        )
        assertEquals("DEPOSIT", response.path("type").asString())
        assertMoney("50.00", response.path("amount"))
        assertIsoUtcTimestamp(response.path("createdAt"))
        assertEntries(response, funding.id to "-50.00", user.accountId to "50.00")

        assertEquals(5000L, balanceOf(user.accountId))
        assertEquals(funding.balance - 5000L, balanceOf(funding.id), "FUNDING balance must decrease by exactly 5000")
        val txRow = jdbc.queryForMap("SELECT type, amount FROM transactions WHERE id = ?", txId)
        assertEquals("DEPOSIT", txRow["type"])
        assertEquals(5000L, longValue(txRow["amount"]))
        val entryRows = jdbc.queryForList(
            "SELECT account_id, amount FROM transaction_entries WHERE transaction_id = ? ORDER BY amount",
            txId,
        ).map { longValue(it["account_id"]) to longValue(it["amount"]) }
        assertEquals(listOf(funding.id to -5000L, user.accountId to 5000L), entryRows)
    }

    @Test
    fun `AC-3 a second deposit adds to the balance`() {
        val user = newUser()
        assertEquals(201, deposit(user.id, """{"amount":"50.00"}""", newKey()).status.value())

        val second = deposit(user.id, """{"amount":"30.25"}""", newKey())

        assertEquals(201, second.status.value(), "status; body: ${body(second)}")
        assertAccountBalance(user, "80.25", 8025L)
    }

    @Test
    fun `AC-4 reads a transaction by id and returns 404 problem detail for an unknown id`() {
        val user = newUser()
        val created = deposit(user.id, """{"amount":"50.00"}""", newKey())
        assertEquals(201, created.status.value(), "status; body: ${body(created)}")
        val createdBody = json(created)

        val read = getTransaction(createdBody.path("id").asLong())

        assertEquals(200, read.status.value(), "status; body: ${body(read)}")
        assertEquals(createdBody, json(read))

        assertProblem(getTransaction(UNKNOWN_ID), 404)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-5 .. AC-7: idempotency and concurrency
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-5 a repeated deposit with the same key is replayed and credits the account once`() {
        val user = newUser()
        val key = newKey()
        val request = """{"amount":"50.00"}"""
        val first = deposit(user.id, request, key)
        assertEquals(201, first.status.value(), "status; body: ${body(first)}")
        val firstLocation = assertNotNull(first.responseHeaders.getFirst("Location"), "Location header missing")

        val second = deposit(user.id, request, key)

        assertEquals(201, second.status.value(), "status; body: ${body(second)}")
        assertEquals("true", second.responseHeaders.getFirst(REPLAYED))
        assertEquals(firstLocation, second.responseHeaders.getFirst("Location"))
        assertEquals(json(first), json(second))
        assertEquals(5000L, balanceOf(user.accountId))
        assertEquals(1L, entryCount(user.accountId))
    }

    @Test
    fun `AC-6 concurrent identical deposits with the same key credit the account once`() {
        val user = newUser()
        val key = newKey()

        val results = concurrently(CONCURRENT_SAME_KEY) { deposit(user.id, """{"amount":"10.00"}""", key) }

        val created = results.filter { it.status.value() == 201 }
        results.filter { it.status.value() != 201 }.forEach { assertProblem(it, 409, "Request in progress") }
        assertTrue(created.isNotEmpty(), "at least one response must be 201")
        val firstBody = json(created.first())
        created.forEach { assertEquals(firstBody, json(it)) }
        assertEquals(1000L, balanceOf(user.accountId))
        assertEquals(1L, entryCount(user.accountId))
    }

    @Test
    fun `AC-7 concurrent deposits with different keys never lose or duplicate money`() {
        val user = newUser()

        val results = concurrently(CONCURRENT_DIFFERENT_KEYS) { deposit(user.id, """{"amount":"1.00"}""", newKey()) }

        results.filter { it.status.value() != 201 }.forEach { assertProblem(it, 409) }
        val createdCount = results.count { it.status.value() == 201 }.toLong()
        assertTrue(createdCount >= 1, "at least one response must be 201")
        assertEquals(100L * createdCount, balanceOf(user.accountId), "balance must be 100 x number of 201 responses")
        assertEquals(createdCount, entryCount(user.accountId), "entries must equal the number of 201 responses")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-8: amount validation
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-8 (a) rejects a deposit without amount with 400 and no side effects`() {
        assertDepositRejected("""{}""")
    }

    @Test
    fun `AC-8 (b) rejects amount 0_00 with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"0.00"}""")
    }

    @Test
    fun `AC-8 (c) rejects amount -1_00 with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"-1.00"}""")
    }

    @Test
    fun `AC-8 (d) rejects amount 10000000_01 with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"10000000.01"}""")
    }

    @Test
    fun `AC-8 (e) rejects amount as JSON number 50 with 400 and no side effects`() {
        assertDepositRejected("""{"amount":50}""")
    }

    @Test
    fun `AC-8 (f) rejects amount as JSON number 50_00 with 400 and no side effects`() {
        assertDepositRejected("""{"amount":50.00}""")
    }

    @Test
    fun `AC-8 (g) rejects amount string 50 without fraction digits with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"50"}""")
    }

    @Test
    fun `AC-8 (h) rejects amount string 50_5 with one fraction digit with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"50.5"}""")
    }

    @Test
    fun `AC-8 (i) rejects amount string 50_000 with three fraction digits with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"50.000"}""")
    }

    @Test
    fun `AC-8 (j) rejects amount with a decimal comma with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"50,00"}""")
    }

    @Test
    fun `AC-8 (k) rejects malformed JSON with 400 and no side effects`() {
        assertDepositRejected("""{"amount":"50.00"""")
    }

    @Test
    fun `AC-8 accepts the boundary amounts 0_01 and 10000000_00`() {
        val min = deposit(newUser().id, """{"amount":"0.01"}""", newKey())
        assertEquals(201, min.status.value(), "status; body: ${body(min)}")
        assertEquals(1L, transactionAmount(json(min).path("id").asLong()))

        val max = deposit(newUser().id, """{"amount":"10000000.00"}""", newKey())
        assertEquals(201, max.status.value(), "status; body: ${body(max)}")
        assertEquals(1000000000L, transactionAmount(json(max).path("id").asLong()))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-9 .. AC-10: unknown user, missing Idempotency-Key
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-9 a deposit to an unknown user returns 404 and changes nothing`() {
        assertEquals(0L, count("SELECT count(*) FROM users WHERE id = ?", UNKNOWN_ID))
        val transactionsBefore = count("SELECT count(*) FROM transactions")
        val fundingBefore = fundingAccount()

        val result = deposit(UNKNOWN_ID, """{"amount":"50.00"}""", newKey())

        assertProblem(result, 404)
        assertEquals(transactionsBefore, count("SELECT count(*) FROM transactions"), "transactions row count changed")
        assertEquals(fundingBefore.balance, balanceOf(fundingBefore.id), "FUNDING balance changed")
    }

    @Test
    fun `AC-10 a deposit without Idempotency-Key returns 400 and leaves the balance unchanged`() {
        val user = newUser()

        val result = deposit(user.id, """{"amount":"50.00"}""", key = null)

        assertProblem(result, 400, "Idempotency-Key header required")
        assertEquals(0L, balanceOf(user.accountId))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-11: ledger invariants over the whole database
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-11 every transaction is balanced and every account balance equals the sum of its entries`() {
        val first = newUser()
        val second = newUser()
        assertEquals(201, deposit(first.id, """{"amount":"12.34"}""", newKey()).status.value())
        assertEquals(201, deposit(first.id, """{"amount":"0.66"}""", newKey()).status.value())
        assertEquals(201, deposit(second.id, """{"amount":"7.00"}""", newKey()).status.value())
        assertTrue(count("SELECT count(*) FROM transactions") >= 3, "the deposits above must be in transactions")

        val unbalancedTransactions = jdbc.queryForList(
            """
            SELECT t.id
            FROM transactions t
            LEFT JOIN transaction_entries e ON e.transaction_id = t.id
            GROUP BY t.id, t.amount
            HAVING count(e.id) <> 2
                OR COALESCE(SUM(e.amount), 0) <> 0
                OR COALESCE(MAX(e.amount), 0) <> t.amount
            """.trimIndent(),
            Long::class.javaObjectType,
        )
        assertEquals(
            emptyList(),
            unbalancedTransactions,
            "transactions without exactly 2 entries summing to 0 with the positive one equal to the amount",
        )

        val inconsistentAccounts = jdbc.queryForList(
            """
            SELECT a.id
            FROM accounts a
            LEFT JOIN transaction_entries e ON e.account_id = a.id
            GROUP BY a.id, a.balance
            HAVING a.balance <> COALESCE(SUM(e.amount), 0)
            """.trimIndent(),
            Long::class.javaObjectType,
        )
        assertEquals(emptyList(), inconsistentAccounts, "accounts whose balance differs from the sum of their entries")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-12: exact money arithmetic in integer minor units
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-12 small deposits add up exactly and are stored as integers`() {
        val user = newUser()
        val txIds = mutableListOf<Long>()

        for (amount in listOf("0.10", "0.20")) {
            val result = deposit(user.id, """{"amount":"$amount"}""", newKey())
            assertEquals(201, result.status.value(), "status; body: ${body(result)}")
            txIds += json(result).path("id").asLong()
        }
        assertAccountBalance(user, "0.30", 30L)

        repeat(10) {
            val result = deposit(user.id, """{"amount":"0.29"}""", newKey())
            assertEquals(201, result.status.value(), "status; body: ${body(result)}")
            val response = json(result)
            assertMoney("0.29", response.path("amount"))
            val entries = response.path("entries")
            assertEquals(2, entries.size(), "entries: $entries")
            assertMoney("-0.29", entries.path(0).path("amount"))
            assertMoney("0.29", entries.path(1).path("amount"))
            txIds += response.path("id").asLong()
        }
        assertAccountBalance(user, "3.20", 320L)

        assertEquals(
            "integer",
            jdbc.queryForObject("SELECT typeof(balance) FROM accounts WHERE id = ?", String::class.java, user.accountId),
            "typeof(accounts.balance)",
        )
        for (txId in txIds) {
            assertEquals(
                listOf("integer"),
                jdbc.queryForList("SELECT typeof(amount) FROM transactions WHERE id = ?", String::class.java, txId),
                "typeof(transactions.amount) for transaction $txId",
            )
            assertEquals(
                listOf("integer", "integer"),
                jdbc.queryForList(
                    "SELECT typeof(amount) FROM transaction_entries WHERE transaction_id = ?",
                    String::class.java,
                    txId,
                ),
                "typeof(transaction_entries.amount) for transaction $txId",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private data class TestUser(val id: Long, val accountId: Long)

    private data class SystemAccount(val id: Long, val balance: Long)

    /** Registers a new user through the STORY-001 API and returns its ID and the ID of its account. */
    private fun newUser(): TestUser {
        val email = "deposit-${UUID.randomUUID()}@example.com"
        val result = postJson("/api/v1/users", """{"name":"Depositor","email":"$email"}""", newKey())
        assertEquals(201, result.status.value(), "user registration failed; body: ${body(result)}")
        val userId = json(result).path("id").asLong()
        val accountId = assertNotNull(
            jdbc.queryForObject("SELECT id FROM accounts WHERE user_id = ?", Long::class.javaObjectType, userId),
        )
        return TestUser(userId, accountId)
    }

    private fun fundingAccount(): SystemAccount {
        val rows = jdbc.queryForList("SELECT id, balance FROM accounts WHERE kind = 'FUNDING'")
        assertEquals(1, rows.size, "exactly one FUNDING account expected")
        val row = rows.single()
        return SystemAccount(longValue(row["id"]), longValue(row["balance"]))
    }

    private fun deposit(userId: Long, body: String, key: String?): EntityExchangeResult<ByteArray> =
        postJson("/api/v1/users/$userId/account/deposits", body, key)

    private fun postJson(path: String, body: String, key: String?): EntityExchangeResult<ByteArray> =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> if (key != null) headers.set(IDEMPOTENCY_KEY, key) }
            .body(body.toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()

    private fun getTransaction(id: Long): EntityExchangeResult<ByteArray> =
        client.get().uri("/api/v1/transactions/{id}", id)
            .exchange()
            .expectBody()
            .returnResult()

    /** Runs [count] requests at the same time on virtual threads and returns all responses. */
    private fun concurrently(
        count: Int,
        request: () -> EntityExchangeResult<ByteArray>,
    ): List<EntityExchangeResult<ByteArray>> {
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<EntityExchangeResult<ByteArray>>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (1..count).map {
            Thread.ofVirtual().start {
                try {
                    start.await()
                    results.add(request())
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        start.countDown()
        threads.forEach { assertTrue(it.join(Duration.ofSeconds(60)), "request thread did not finish in time") }
        assertTrue(errors.isEmpty(), "requests failed on the client side: $errors")
        assertEquals(count, results.size)
        return results.toList()
    }

    /** A deposit with [body] to a new user with balance 0, which must be rejected with 400 and change nothing. */
    private fun assertDepositRejected(body: String) {
        val user = newUser()
        val transactionsBefore = count("SELECT count(*) FROM transactions")

        val result = deposit(user.id, body, newKey())

        assertProblem(result, 400)
        assertEquals(0L, balanceOf(user.accountId), "account balance changed")
        assertEquals(transactionsBefore, count("SELECT count(*) FROM transactions"), "transactions row count changed")
    }

    private fun assertAccountBalance(user: TestUser, expected: String, expectedCents: Long) {
        val result = client.get().uri("/api/v1/users/{id}/account", user.id)
            .exchange()
            .expectBody()
            .returnResult()
        assertEquals(200, result.status.value(), "status; body: ${body(result)}")
        assertMoney(expected, json(result).path("balance"))
        assertEquals(expectedCents, balanceOf(user.accountId))
    }

    /** Checks that [response] has exactly the [expected] entries (account ID to money string), in this order. */
    private fun assertEntries(response: JsonNode, vararg expected: Pair<Long, String>) {
        val entries = response.path("entries")
        assertTrue(entries.isArray, "entries must be an array, was: $entries")
        assertEquals(expected.size, entries.size(), "entries: $entries")
        expected.forEachIndexed { index, (accountId, amount) ->
            val entry = entries.path(index)
            val accountIdNode = entry.path("accountId")
            assertTrue(accountIdNode.isIntegralNumber, "entries[$index].accountId must be an integer, was: $accountIdNode")
            assertEquals(accountId, accountIdNode.asLong(), "entries[$index].accountId")
            assertMoney(amount, entry.path("amount"))
        }
    }

    private fun assertMoney(expected: String, node: JsonNode) {
        assertTrue(node.isString, "money must be a JSON string, was: $node")
        assertEquals(expected, node.asString())
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

    private fun assertIsoUtcTimestamp(node: JsonNode) {
        assertTrue(node.isString, "timestamp must be a JSON string, was: $node")
        val text = node.asString()
        assertTrue(ISO_UTC.matches(text), "timestamp must be ISO-8601 UTC ending in Z, was: $text")
        Instant.parse(text)
    }

    private fun balanceOf(accountId: Long): Long =
        longValue(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long::class.javaObjectType, accountId))

    private fun entryCount(accountId: Long): Long =
        count("SELECT count(*) FROM transaction_entries WHERE account_id = ?", accountId)

    private fun transactionAmount(txId: Long): Long =
        longValue(jdbc.queryForObject("SELECT amount FROM transactions WHERE id = ?", Long::class.javaObjectType, txId))

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L

    private fun json(result: EntityExchangeResult<ByteArray>): JsonNode = mapper.readTree(result.responseBodyContent)

    private fun body(result: EntityExchangeResult<ByteArray>): String =
        result.responseBodyContent?.toString(Charsets.UTF_8) ?: ""

    private fun longValue(value: Any?): Long = (assertNotNull(value) as Number).toLong()

    private fun newKey(): String = UUID.randomUUID().toString()

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val REPLAYED = "Idempotent-Replayed"
        const val CONCURRENT_SAME_KEY = 10
        const val CONCURRENT_DIFFERENT_KEYS = 5
        const val UNKNOWN_ID = Long.MAX_VALUE
        val ISO_UTC = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")
    }
}
