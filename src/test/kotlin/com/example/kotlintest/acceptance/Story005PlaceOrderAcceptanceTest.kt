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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for STORY-005: place and pay an order
 * (`POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/orders`, `orderId` in `GET /api/v1/transactions/{id}`).
 *
 * Black-box: HTTP API + plain SQL only. The database is shared between tests and test classes, so nothing is deleted:
 * every test registers its own users (STORY-001 API), funds them through the deposit API (STORY-004), creates its own
 * goods (STORY-003 API) and uses its own idempotency keys, so the ledger invariants hold. The `REVENUE` balance is
 * checked as a difference (before/after). API money values are strings in major units, database values are integers
 * in minor units (`"15.00"` <-> `1500`).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class Story005PlaceOrderAcceptanceTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val mapper = JsonMapper.shared()

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 .. AC-3: placing an order, its payment transaction, the stored unit price
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-1 places an order and returns 201 with Location, the order, and the ledger and stock changes`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        val revenue = revenueAccount()

        val result = placeOrder(orderJson(user.id, goodId, 3), newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        val idNode = response.path("id")
        assertTrue(idNode.isIntegralNumber, "id must be an integer, was: $idNode")
        val orderId = idNode.asLong()
        val location = assertNotNull(result.responseHeaders.getFirst("Location"), "Location header missing")
        assertTrue(
            URI.create(location).path.endsWith("/api/v1/orders/$orderId"),
            "Location path must end with /api/v1/orders/$orderId, was: $location",
        )
        assertEquals(user.id, response.path("userId").asLong(), "userId")
        assertEquals(goodId, response.path("goodId").asLong(), "goodId")
        assertEquals(3, response.path("quantity").asInt(), "quantity")
        assertMoney("15.00", response.path("unitPrice"))
        assertMoney("45.00", response.path("totalAmount"))
        assertEquals("PAID", response.path("status").asString())
        val paymentNode = response.path("paymentTransactionId")
        assertTrue(paymentNode.isIntegralNumber, "paymentTransactionId must be a non-null integer, was: $paymentNode")
        val paymentTxId = paymentNode.asLong()
        assertTrue(response.has("refundTransactionId"), "refundTransactionId must be present")
        assertTrue(response.get("refundTransactionId").isNull, "refundTransactionId must be null")
        assertIsoUtcTimestamp(response.path("createdAt"))
        assertIsoUtcTimestamp(response.path("updatedAt"))

        assertAccountBalance(user, "55.00", 5500L)
        assertEquals(7L, stockOf(goodId), "goods.quantity")
        assertEquals(revenue.balance + 4500L, balanceOf(revenue.id), "REVENUE balance must increase by exactly 4500")

        val orderRow = jdbc.queryForMap(
            "SELECT user_id, good_id, quantity, status, unit_price, total_amount FROM orders WHERE id = ?",
            orderId,
        )
        assertEquals(user.id, longValue(orderRow["user_id"]))
        assertEquals(goodId, longValue(orderRow["good_id"]))
        assertEquals(3L, longValue(orderRow["quantity"]))
        assertEquals("PAID", orderRow["status"])
        assertEquals(1500L, longValue(orderRow["unit_price"]))
        assertEquals(4500L, longValue(orderRow["total_amount"]))

        val txRows = jdbc.queryForList("SELECT id, type, amount FROM transactions WHERE order_id = ?", orderId)
        assertEquals(1, txRows.size, "exactly one transaction with order_id = $orderId expected: $txRows")
        val txRow = txRows.single()
        assertEquals(paymentTxId, longValue(txRow["id"]), "transactions.id must equal paymentTransactionId")
        assertEquals("ORDER_PAYMENT", txRow["type"])
        assertEquals(4500L, longValue(txRow["amount"]))
        assertEquals(
            listOf(user.accountId to -4500L, revenue.id to 4500L),
            entryRows(paymentTxId),
            "entries of the payment transaction",
        )
    }

    @Test
    fun `AC-2 the payment transaction has orderId and the order entries, a deposit transaction has orderId null`() {
        val user = newUser()
        val depositTxId = deposit(user, "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        val revenue = revenueAccount()
        val created = placeOrder(orderJson(user.id, goodId, 3), newKey())
        assertEquals(201, created.status.value(), "status; body: ${body(created)}")
        val order = json(created)
        val orderId = order.path("id").asLong()
        val paymentTxId = order.path("paymentTransactionId").asLong()

        val payment = getTransaction(paymentTxId)

        assertEquals(200, payment.status.value(), "status; body: ${body(payment)}")
        val paymentBody = json(payment)
        assertEquals("ORDER_PAYMENT", paymentBody.path("type").asString())
        assertMoney("45.00", paymentBody.path("amount"))
        val orderIdNode = paymentBody.path("orderId")
        assertTrue(orderIdNode.isIntegralNumber, "orderId must be an integer, was: $orderIdNode")
        assertEquals(orderId, orderIdNode.asLong(), "orderId")
        assertEntries(paymentBody, user.accountId to "-45.00", revenue.id to "45.00")

        val depositTx = getTransaction(depositTxId)

        assertEquals(200, depositTx.status.value(), "status; body: ${body(depositTx)}")
        val depositBody = json(depositTx)
        assertEquals("DEPOSIT", depositBody.path("type").asString())
        assertTrue(depositBody.has("orderId"), "orderId must be present in a deposit transaction")
        assertTrue(depositBody.get("orderId").isNull, "orderId of a deposit must be null")
    }

    @Test
    fun `AC-3 a later price change does not change the unit price and total of an existing order`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        val created = placeOrder(orderJson(user.id, goodId, 3), newKey())
        assertEquals(201, created.status.value(), "status; body: ${body(created)}")
        val order = json(created)
        val patched = client.patch().uri("/api/v1/goods/{id}", goodId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"price":"20.00"}""".toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()
        assertEquals(200, patched.status.value(), "price change failed; body: ${body(patched)}")

        val read = getOrder(order.path("id").asLong())

        assertEquals(200, read.status.value(), "status; body: ${body(read)}")
        val readBody = json(read)
        assertMoney("15.00", readBody.path("unitPrice"))
        assertMoney(order.path("totalAmount").asString(), readBody.path("totalAmount"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4 .. AC-6: insufficient funds / stock, exact balance and stock
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-4 rejects an order the balance does not cover with 409 Insufficient funds and changes nothing`() {
        val user = newUser(balance = "10.00")
        val goodId = newGood(price = "15.00", quantity = 5)
        val before = snapshot()

        val result = placeOrder(orderJson(user.id, goodId, 1), newKey())

        assertProblem(result, 409, "Insufficient funds")
        assertEquals(1000L, balanceOf(user.accountId), "account balance changed")
        assertEquals(5L, stockOf(goodId), "stock changed")
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
    }

    @Test
    fun `AC-5 rejects an order larger than the stock with 409 Insufficient stock and changes nothing`() {
        val user = newUser(balance = "1000.00")
        val goodId = newGood(price = "1.00", quantity = 2)
        val before = snapshot()

        val result = placeOrder(orderJson(user.id, goodId, 3), newKey())

        assertProblem(result, 409, "Insufficient stock")
        assertEquals(100000L, balanceOf(user.accountId), "account balance changed")
        assertEquals(2L, stockOf(goodId), "stock changed")
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
    }

    @Test
    fun `AC-5 returns Insufficient stock when both the stock and the balance are too low`() {
        val goodId = newGood(price = "1.00", quantity = 2)
        val poorUser = newUser()
        val before = snapshot()

        val result = placeOrder(orderJson(poorUser.id, goodId, 3), newKey())

        assertProblem(result, 409, "Insufficient stock")
        assertEquals(0L, balanceOf(poorUser.accountId), "account balance changed")
        assertEquals(2L, stockOf(goodId), "stock changed")
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
    }

    @Test
    fun `AC-6 an order that uses the whole balance and the whole stock succeeds`() {
        val user = newUser(balance = "30.00")
        val goodId = newGood(price = "10.00", quantity = 3)

        val result = placeOrder(orderJson(user.id, goodId, 3), newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        assertMoney("30.00", json(result).path("totalAmount"))
        assertAccountBalance(user, "0.00", 0L)
        assertEquals(0L, stockOf(goodId), "stock")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-7 .. AC-10: idempotency and concurrency
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-7 a repeated order with the same key is replayed and pays once`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        val key = newKey()
        val request = orderJson(user.id, goodId, 3)
        val first = placeOrder(request, key)
        assertEquals(201, first.status.value(), "status; body: ${body(first)}")
        val firstLocation = assertNotNull(first.responseHeaders.getFirst("Location"), "Location header missing")

        val second = placeOrder(request, key)

        assertEquals(201, second.status.value(), "status; body: ${body(second)}")
        assertEquals("true", second.responseHeaders.getFirst(REPLAYED))
        assertEquals(firstLocation, second.responseHeaders.getFirst("Location"))
        assertEquals(json(first), json(second))
        assertEquals(5500L, balanceOf(user.accountId), "the balance must be debited once")
        assertEquals(7L, stockOf(goodId), "the stock must be decreased once")
        assertEquals(1L, orderCount(user.id), "orders of the user")
        assertEquals(1L, paymentEntryCount(user.accountId), "ORDER_PAYMENT entries of the user account")
    }

    @Test
    fun `AC-8 concurrent identical orders with the same key pay once`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "10.00", quantity = 10)
        val key = newKey()
        val request = orderJson(user.id, goodId, 1)

        val results = concurrently(CONCURRENT_SAME_KEY) { placeOrder(request, key) }

        val created = results.filter { it.status.value() == 201 }
        results.filter { it.status.value() != 201 }.forEach { assertProblem(it, 409, "Request in progress") }
        assertTrue(created.isNotEmpty(), "at least one response must be 201")
        val firstBody = json(created.first())
        created.forEach { assertEquals(firstBody, json(it)) }
        assertEquals(1L, orderCount(user.id), "orders of the user")
        assertEquals(9000L, balanceOf(user.accountId), "account balance")
        assertEquals(9L, stockOf(goodId), "stock")
    }

    @Test
    fun `AC-9 concurrent orders with different keys never overdraw the account`() {
        val user = newUser(balance = "10.00")
        val goodId = newGood(price = "7.00", quantity = 10)
        val request = orderJson(user.id, goodId, 1)

        val results = concurrently(CONCURRENT_DIFFERENT_KEYS) { placeOrder(request, newKey()) }

        assertEquals(1, results.count { it.status.value() == 201 }, "exactly one response must be 201: ${statuses(results)}")
        results.filter { it.status.value() != 201 }.forEach { assertProblem(it, 409) }
        assertEquals(300L, balanceOf(user.accountId), "account balance")
        assertEquals(9L, stockOf(goodId), "stock")
        assertEquals(1L, orderCount(user.id), "orders of the user")
    }

    @Test
    fun `AC-10 concurrent orders of two users never oversell the last item`() {
        val goodId = newGood(price = "1.00", quantity = 1)
        val first = newUser(balance = "10.00")
        val second = newUser(balance = "10.00")
        val requests = listOf(orderJson(first.id, goodId, 1), orderJson(second.id, goodId, 1))
        val next = AtomicInteger()

        val results = concurrently(2) { placeOrder(requests[next.getAndIncrement()], newKey()) }

        assertEquals(1, results.count { it.status.value() == 201 }, "exactly one response must be 201: ${statuses(results)}")
        results.filter { it.status.value() != 201 }.forEach { assertProblem(it, 409) }
        assertEquals(0L, stockOf(goodId), "stock")
        assertEquals(
            listOf(900L, 1000L),
            listOf(balanceOf(first.accountId), balanceOf(second.accountId)).sorted(),
            "exactly one of the two users must be debited",
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-11 .. AC-12: unknown user / good, invalid input, missing Idempotency-Key
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-11 an order for an unknown user returns 404 and changes nothing`() {
        val goodId = newGood(price = "1.00", quantity = 10)
        val unknownUserId = unknownId("users")
        val before = snapshot()

        val result = placeOrder(orderJson(unknownUserId, goodId, 1), newKey())

        assertProblem(result, 404)
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
        assertEquals(10L, stockOf(goodId), "stock changed")
    }

    @Test
    fun `AC-11 an order for an unknown good returns 404 and changes nothing`() {
        val user = newUser(balance = "100.00")
        val unknownGoodId = unknownId("goods")
        val before = snapshot()

        val result = placeOrder(orderJson(user.id, unknownGoodId, 1), newKey())

        assertProblem(result, 404)
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
        assertEquals(10000L, balanceOf(user.accountId), "account balance changed")
    }

    @Test
    fun `AC-12 (a) rejects an order without userId with 400 and changes nothing`() {
        assertOrderRejected { _, goodId -> """{"goodId":$goodId,"quantity":1}""" }
    }

    @Test
    fun `AC-12 (b) rejects an order without goodId with 400 and changes nothing`() {
        assertOrderRejected { userId, _ -> """{"userId":$userId,"quantity":1}""" }
    }

    @Test
    fun `AC-12 (c) rejects an order without quantity with 400 and changes nothing`() {
        assertOrderRejected { userId, goodId -> """{"userId":$userId,"goodId":$goodId}""" }
    }

    @Test
    fun `AC-12 (d) rejects quantity 0 with 400 and changes nothing`() {
        assertOrderRejected { userId, goodId -> orderJson(userId, goodId, 0) }
    }

    @Test
    fun `AC-12 (e) rejects quantity -1 with 400 and changes nothing`() {
        assertOrderRejected { userId, goodId -> orderJson(userId, goodId, -1) }
    }

    @Test
    fun `AC-12 (f) rejects quantity 10001 with 400 and changes nothing`() {
        assertOrderRejected { userId, goodId -> orderJson(userId, goodId, 10001) }
    }

    @Test
    fun `AC-12 (g) rejects malformed JSON with 400 and changes nothing`() {
        assertOrderRejected { userId, goodId -> """{"userId":$userId,"goodId":$goodId,"quantity":1""" }
    }

    @Test
    fun `AC-12 rejects a valid order without Idempotency-Key with 400 and changes nothing`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "1.00", quantity = 10)
        val before = snapshot()

        val result = placeOrder(orderJson(user.id, goodId, 1), key = null)

        assertProblem(result, 400, "Idempotency-Key header required")
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
        assertEquals(10000L, balanceOf(user.accountId), "account balance changed")
        assertEquals(10L, stockOf(goodId), "stock changed")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-13 .. AC-14: reading and listing orders
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-13 reads an order by id and returns 404 problem detail for an unknown id`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        val created = placeOrder(orderJson(user.id, goodId, 2), newKey())
        assertEquals(201, created.status.value(), "status; body: ${body(created)}")
        val createdBody = json(created)

        val read = getOrder(createdBody.path("id").asLong())

        assertEquals(200, read.status.value(), "status; body: ${body(read)}")
        assertEquals(createdBody, json(read))

        assertProblem(getOrder(unknownId("orders")), 404)
    }

    @Test
    fun `AC-14 lists the orders of one user with pagination`() {
        val userA = newUser(balance = "100.00")
        val userB = newUser(balance = "100.00")
        val goodId = newGood(price = "1.00", quantity = 100)
        val firstA = placeOrderOk(orderJson(userA.id, goodId, 1))
        val orderB = placeOrderOk(orderJson(userB.id, goodId, 2))
        val secondA = placeOrderOk(orderJson(userA.id, goodId, 3))
        assertTrue(firstA < secondA, "order IDs must ascend in creation order")
        assertTrue(orderB != firstA && orderB != secondA)

        val all = getList("/api/v1/orders?userId=${userA.id}&sort=id,asc")

        assertEquals(200, all.status.value(), "status; body: ${body(all)}")
        val allBody = json(all)
        assertEquals(listOf(firstA, secondA), contentIds(allBody), "content of user A's orders")
        contentOf(allBody).forEach { assertEquals(userA.id, it.path("userId").asLong(), "userId of $it") }
        assertEquals(2L, allBody.path("page").path("totalElements").asLong(), "page.totalElements")

        val firstPage = getList("/api/v1/orders?userId=${userA.id}&sort=id,asc&size=1")

        assertEquals(200, firstPage.status.value(), "status; body: ${body(firstPage)}")
        val firstPageBody = json(firstPage)
        assertEquals(1, contentOf(firstPageBody).size, "content size with size=1")
        assertEquals(2L, firstPageBody.path("page").path("totalPages").asLong(), "page.totalPages")

        val unknownUser = getList("/api/v1/orders?userId=${unknownId("users")}")

        assertEquals(200, unknownUser.status.value(), "status; body: ${body(unknownUser)}")
        val unknownUserBody = json(unknownUser)
        assertEquals(emptyList(), contentOf(unknownUserBody), "content for an unknown user")
        assertEquals(0L, unknownUserBody.path("page").path("totalElements").asLong(), "page.totalElements")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-15 .. AC-16: database constraints and ledger invariants
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-15 the database rejects a second ORDER_PAYMENT transaction for the same order`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        val orderId = placeOrderOk(orderJson(user.id, goodId, 3))
        assertEquals(
            1L,
            count("SELECT count(*) FROM transactions WHERE order_id = ? AND type = 'ORDER_PAYMENT'", orderId),
            "the order must have its ORDER_PAYMENT transaction",
        )

        val error = failureOf(
            "INSERT INTO transactions (type, amount, created_at, order_id) VALUES ('ORDER_PAYMENT', 4500, ?, ?)",
            "2026-11-09T12:00:00.000Z",
            orderId,
        )

        assertConstraintViolation(error)
    }

    @Test
    fun `AC-15 the database rejects a negative user account balance`() {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "15.00", quantity = 10)
        placeOrderOk(orderJson(user.id, goodId, 3))

        val error = failureOf("UPDATE accounts SET balance = -1 WHERE id = ?", user.accountId)

        assertConstraintViolation(error)
        assertEquals(5500L, balanceOf(user.accountId), "account balance")
    }

    @Test
    fun `AC-16 every transaction is balanced, every balance equals its entries, nothing is negative`() {
        val first = newUser(balance = "50.00")
        val second = newUser(balance = "20.00")
        val goodA = newGood(price = "15.00", quantity = 10)
        val goodB = newGood(price = "0.57", quantity = 5)
        placeOrderOk(orderJson(first.id, goodA, 2))
        placeOrderOk(orderJson(first.id, goodB, 5))
        placeOrderOk(orderJson(second.id, goodA, 1))
        assertTrue(
            count("SELECT count(*) FROM transactions WHERE type = 'ORDER_PAYMENT'") >= 3,
            "the orders above must be in transactions",
        )

        val unbalancedTransactions = jdbc.queryForList(
            """
            SELECT t.id
            FROM transactions t
            LEFT JOIN transaction_entries e ON e.transaction_id = t.id
            GROUP BY t.id
            HAVING count(e.id) <> 2 OR COALESCE(SUM(e.amount), 0) <> 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )
        assertEquals(emptyList(), unbalancedTransactions, "transactions without exactly 2 entries summing to 0")

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

        assertEquals(0L, count("SELECT count(*) FROM accounts WHERE kind = 'USER' AND balance < 0"), "negative user balances")
        assertEquals(0L, count("SELECT count(*) FROM goods WHERE quantity < 0"), "negative goods quantities")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-17: exact money arithmetic in integer minor units
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-17 computes the total exactly in integer minor units`() {
        val user = newUser(balance = "10.00")
        val goodId = newGood(price = "0.57", quantity = 10)

        val result = placeOrder(orderJson(user.id, goodId, 3), newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        assertMoney("0.57", response.path("unitPrice"))
        assertMoney("1.71", response.path("totalAmount"))
        assertAccountBalance(user, "8.29", 829L)
        val row = jdbc.queryForMap(
            "SELECT unit_price, total_amount, typeof(unit_price) AS unit_price_type, " +
                "typeof(total_amount) AS total_amount_type FROM orders WHERE id = ?",
            response.path("id").asLong(),
        )
        assertEquals(57L, longValue(row["unit_price"]), "unit_price")
        assertEquals(171L, longValue(row["total_amount"]), "total_amount")
        assertEquals("integer", row["unit_price_type"], "typeof(unit_price)")
        assertEquals("integer", row["total_amount_type"], "typeof(total_amount)")
    }

    @Test
    fun `AC-17 handles the largest price exactly`() {
        val user = newUser(balance = "10000000.00")
        val goodId = newGood(price = "9999999.99", quantity = 1)

        val result = placeOrder(orderJson(user.id, goodId, 1), newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        assertMoney("9999999.99", json(result).path("totalAmount"))
        assertAccountBalance(user, "0.01", 1L)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private data class TestUser(val id: Long, val accountId: Long)

    private data class SystemAccount(val id: Long, val balance: Long)

    /** Row counts that must not change when an order is rejected. */
    private data class Snapshot(val orders: Long, val transactions: Long, val entries: Long)

    /**
     * Registers a new user through the STORY-001 API and, if [balance] is not `"0.00"`, deposits it through the
     * STORY-004 API. Returns the user ID and the ID of the user's account.
     */
    private fun newUser(balance: String = "0.00"): TestUser {
        val email = "order-${UUID.randomUUID()}@example.com"
        val result = postJson("/api/v1/users", """{"name":"Buyer","email":"$email"}""", newKey())
        assertEquals(201, result.status.value(), "user registration failed; body: ${body(result)}")
        val userId = json(result).path("id").asLong()
        val accountId = assertNotNull(
            jdbc.queryForObject("SELECT id FROM accounts WHERE user_id = ?", Long::class.javaObjectType, userId),
        )
        val user = TestUser(userId, accountId)
        if (balance != "0.00") {
            deposit(user, balance)
        }
        return user
    }

    /** Deposits [amount] to [user] through the STORY-004 API and returns the deposit transaction ID. */
    private fun deposit(user: TestUser, amount: String): Long {
        val result = postJson("/api/v1/users/${user.id}/account/deposits", """{"amount":"$amount"}""", newKey())
        assertEquals(201, result.status.value(), "deposit failed; body: ${body(result)}")
        return json(result).path("id").asLong()
    }

    /** Creates a good through the STORY-003 API and returns its ID. */
    private fun newGood(price: String, quantity: Int): Long {
        val name = "Order good ${UUID.randomUUID()}"
        val result = postJson("/api/v1/goods", """{"name":"$name","quantity":$quantity,"price":"$price"}""", newKey())
        assertEquals(201, result.status.value(), "good creation failed; body: ${body(result)}")
        return json(result).path("id").asLong()
    }

    private fun revenueAccount(): SystemAccount {
        val rows = jdbc.queryForList("SELECT id, balance FROM accounts WHERE kind = 'REVENUE'")
        assertEquals(1, rows.size, "exactly one REVENUE account expected")
        val row = rows.single()
        return SystemAccount(longValue(row["id"]), longValue(row["balance"]))
    }

    private fun orderJson(userId: Long, goodId: Long, quantity: Int): String =
        """{"userId":$userId,"goodId":$goodId,"quantity":$quantity}"""

    private fun placeOrder(body: String, key: String?): EntityExchangeResult<ByteArray> =
        postJson("/api/v1/orders", body, key)

    /** Places an order that must succeed and returns its ID. */
    private fun placeOrderOk(body: String): Long {
        val result = placeOrder(body, newKey())
        assertEquals(201, result.status.value(), "order failed; body: ${body(result)}")
        return json(result).path("id").asLong()
    }

    private fun postJson(path: String, body: String, key: String?): EntityExchangeResult<ByteArray> =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> if (key != null) headers.set(IDEMPOTENCY_KEY, key) }
            .body(body.toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()

    private fun getOrder(id: Long): EntityExchangeResult<ByteArray> =
        client.get().uri("/api/v1/orders/{id}", id)
            .exchange()
            .expectBody()
            .returnResult()

    private fun getTransaction(id: Long): EntityExchangeResult<ByteArray> =
        client.get().uri("/api/v1/transactions/{id}", id)
            .exchange()
            .expectBody()
            .returnResult()

    private fun getList(uri: String): EntityExchangeResult<ByteArray> =
        client.get().uri(uri)
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

    /**
     * Sends an order built by [body] (from the ID of a funded user and of a good in stock) with a new key; it must be
     * rejected with 400 and change nothing.
     */
    private fun assertOrderRejected(body: (userId: Long, goodId: Long) -> String) {
        val user = newUser(balance = "100.00")
        val goodId = newGood(price = "1.00", quantity = 10)
        val before = snapshot()

        val result = placeOrder(body(user.id, goodId), newKey())

        assertProblem(result, 400)
        assertEquals(before, snapshot(), "row counts of orders / transactions changed")
        assertEquals(10000L, balanceOf(user.accountId), "account balance changed")
        assertEquals(10L, stockOf(goodId), "stock changed")
    }

    /**
     * Runs one SQL statement in a database transaction that is always rolled back, so the shared test database stays
     * consistent even if the statement unexpectedly succeeds. Returns the error, or null if the statement succeeded.
     */
    private fun failureOf(sql: String, vararg args: Any): Throwable? =
        TransactionTemplate(transactionManager).execute { status ->
            status.setRollbackOnly()
            runCatching { jdbc.update(sql, *args) }.exceptionOrNull()
        }

    /** The statement must have failed with a SQLite constraint violation (`SQLITE_CONSTRAINT`, primary code 19). */
    private fun assertConstraintViolation(error: Throwable?) {
        val failure = assertNotNull(error, "the statement must fail with a constraint violation, but it succeeded")
        val isConstraintViolation = generateSequence(failure) { it.cause }.any { cause ->
            cause is SQLException &&
                ((cause.errorCode and 0xFF) == SQLITE_CONSTRAINT || cause.message.orEmpty().contains("constraint failed"))
        }
        assertTrue(isConstraintViolation, "expected a constraint violation, was: $failure")
    }

    private fun assertAccountBalance(user: TestUser, expected: String, expectedCents: Long) {
        val result = client.get().uri("/api/v1/users/{id}/account", user.id)
            .exchange()
            .expectBody()
            .returnResult()
        assertEquals(200, result.status.value(), "status; body: ${body(result)}")
        assertMoney(expected, json(result).path("balance"))
        assertEquals(expectedCents, balanceOf(user.accountId), "accounts.balance")
    }

    /** Checks that [response] has exactly the [expected] entries (account ID to money string), in this order. */
    private fun assertEntries(response: JsonNode, vararg expected: Pair<Long, String>) {
        val entries = response.path("entries")
        assertTrue(entries.isArray, "entries must be an array, was: $entries")
        assertEquals(expected.size, entries.size(), "entries: $entries")
        expected.forEachIndexed { index, (accountId, amount) ->
            val entry = entries.path(index)
            assertEquals(accountId, entry.path("accountId").asLong(), "entries[$index].accountId")
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

    private fun snapshot(): Snapshot = Snapshot(
        orders = count("SELECT count(*) FROM orders"),
        transactions = count("SELECT count(*) FROM transactions"),
        entries = count("SELECT count(*) FROM transaction_entries"),
    )

    /** An ID that no row of [table] has: well above the current maximum. */
    private fun unknownId(table: String): Long {
        val id = count("SELECT COALESCE(MAX(id), 0) + 1000000 FROM $table")
        assertEquals(0L, count("SELECT count(*) FROM $table WHERE id = ?", id))
        return id
    }

    private fun entryRows(txId: Long): List<Pair<Long, Long>> =
        jdbc.queryForList(
            "SELECT account_id, amount FROM transaction_entries WHERE transaction_id = ? ORDER BY amount",
            txId,
        ).map { longValue(it["account_id"]) to longValue(it["amount"]) }

    private fun balanceOf(accountId: Long): Long =
        longValue(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long::class.javaObjectType, accountId))

    private fun stockOf(goodId: Long): Long =
        longValue(jdbc.queryForObject("SELECT quantity FROM goods WHERE id = ?", Long::class.javaObjectType, goodId))

    private fun orderCount(userId: Long): Long = count("SELECT count(*) FROM orders WHERE user_id = ?", userId)

    private fun paymentEntryCount(accountId: Long): Long =
        count(
            """
            SELECT count(*) FROM transaction_entries e
            JOIN transactions t ON t.id = e.transaction_id
            WHERE e.account_id = ? AND t.type = 'ORDER_PAYMENT'
            """.trimIndent(),
            accountId,
        )

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L

    private fun contentOf(page: JsonNode): List<JsonNode> {
        val content = page.path("content")
        assertTrue(content.isArray, "content must be an array, was: $content")
        return (0 until content.size()).map { content.path(it) }
    }

    private fun contentIds(page: JsonNode): List<Long> = contentOf(page).map { it.path("id").asLong() }

    private fun statuses(results: List<EntityExchangeResult<ByteArray>>): List<Int> = results.map { it.status.value() }

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
        const val SQLITE_CONSTRAINT = 19
        val ISO_UTC = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")
    }
}
