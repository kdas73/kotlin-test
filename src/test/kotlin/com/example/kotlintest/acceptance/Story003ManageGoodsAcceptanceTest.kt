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
import java.math.BigDecimal
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
 * Acceptance tests for STORY-003: manage goods (`POST`, `GET`, list and `PATCH` under `/api/v1/goods`).
 *
 * Black-box: HTTP API + plain SQL only. The database is shared between tests and test classes, so nothing is deleted:
 * every test uses its own unique good names and idempotency keys and only asserts on its own data (or compares
 * against SQL queries over the whole table). Request bodies are sent as UTF-8 bytes with characters written literally.
 * `PATCH` requests never send an `Idempotency-Key` header.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class Story003ManageGoodsAcceptanceTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val mapper = JsonMapper.shared()

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 .. AC-2: creation
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-1 creates a good and returns 201 with Location, body and stored row`() {
        val result = postGood("""{"name":"Coffee mug","quantity":10,"price":"15.00"}""", newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        val idNode = response.path("id")
        assertTrue(idNode.isIntegralNumber, "id must be an integer, was: $idNode")
        val id = idNode.asLong()
        val location = assertNotNull(result.responseHeaders.getFirst("Location"), "Location header missing")
        assertTrue(
            URI.create(location).path.endsWith("/api/v1/goods/$id"),
            "Location path must end with /api/v1/goods/$id, was: $location",
        )
        assertEquals("Coffee mug", response.path("name").asString())
        val quantity = response.path("quantity")
        assertTrue(quantity.isIntegralNumber, "quantity must be an integer, was: $quantity")
        assertEquals(10L, quantity.asLong())
        val price = response.path("price")
        assertTrue(price.isString, "price must be a JSON string, was: $price")
        assertEquals("15.00", price.asString())
        assertIsoUtcTimestamp(response.path("createdAt"))
        assertIsoUtcTimestamp(response.path("updatedAt"))
        assertEquals(response.path("createdAt").asString(), response.path("updatedAt").asString())

        val row = goodRow(id)
        assertEquals("Coffee mug", row["name"])
        assertEquals(10L, longValue(row["quantity"]))
        assertEquals(1500L, longValue(row["price"]))
    }

    @Test
    fun `AC-2 trims the name`() {
        val result = postGood("""{"name":"  Tea  ","quantity":5,"price":"3.50"}""", newKey())

        assertEquals(201, result.status.value(), "status; body: ${body(result)}")
        val response = json(result)
        assertEquals("Tea", response.path("name").asString())
        assertEquals("Tea", goodRow(response.path("id").asLong())["name"])
    }

    @Test
    fun `AC-2 accepts quantity 0 with price 0_01 and quantity 1000000 with price 10000000_00`() {
        val minName = uniqueName("Min good")
        val min = postGood("""{"name":"$minName","quantity":0,"price":"0.01"}""", newKey())

        assertEquals(201, min.status.value(), "status; body: ${body(min)}")
        val minResponse = json(min)
        assertEquals(0L, minResponse.path("quantity").asLong())
        assertEquals("0.01", minResponse.path("price").asString())
        val minRow = goodRow(minResponse.path("id").asLong())
        assertEquals(0L, longValue(minRow["quantity"]))
        assertEquals(1L, longValue(minRow["price"]))

        val maxName = uniqueName("Max good")
        val max = postGood("""{"name":"$maxName","quantity":1000000,"price":"10000000.00"}""", newKey())

        assertEquals(201, max.status.value(), "status; body: ${body(max)}")
        val maxResponse = json(max)
        assertEquals(1000000L, maxResponse.path("quantity").asLong())
        assertEquals("10000000.00", maxResponse.path("price").asString())
        val maxRow = goodRow(maxResponse.path("id").asLong())
        assertEquals(1000000L, longValue(maxRow["quantity"]))
        assertEquals(1000000000L, longValue(maxRow["price"]))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-3: create validation errors have no side effects
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-3 (a) rejects a create request without name with 400`() {
        assertCreateRejected("""{"quantity":10,"price":"15.00"}""")
    }

    @Test
    fun `AC-3 (b) rejects a blank name with 400`() {
        assertCreateRejected("""{"name":"  ","quantity":10,"price":"15.00"}""")
    }

    @Test
    fun `AC-3 (c) rejects a name of 201 characters with 400`() {
        val name = "N".repeat(201)
        assertCreateRejected("""{"name":"$name","quantity":10,"price":"15.00"}""")
    }

    @Test
    fun `AC-3 (d) rejects a create request without quantity with 400`() {
        assertCreateRejected("""{"name":"${uniqueName("No quantity")}","price":"15.00"}""")
    }

    @Test
    fun `AC-3 (e) rejects quantity -1 with 400`() {
        assertCreateRejected("""{"name":"${uniqueName("Negative")}","quantity":-1,"price":"15.00"}""")
    }

    @Test
    fun `AC-3 (f) rejects quantity 1000001 with 400`() {
        assertCreateRejected("""{"name":"${uniqueName("Too many")}","quantity":1000001,"price":"15.00"}""")
    }

    @Test
    fun `AC-3 (g) rejects a create request without price with 400`() {
        assertCreateRejected("""{"name":"${uniqueName("No price")}","quantity":10}""")
    }

    @Test
    fun `AC-3 (h) rejects price 0_00 with 400`() {
        assertCreateRejectedForPrice("\"0.00\"")
    }

    @Test
    fun `AC-3 (i) rejects price 10000000_01 with 400`() {
        assertCreateRejectedForPrice("\"10000000.01\"")
    }

    @Test
    fun `AC-3 (j) rejects price as JSON number 15 with 400`() {
        assertCreateRejectedForPrice("15")
    }

    @Test
    fun `AC-3 (k) rejects price as JSON number 15_00 with 400`() {
        assertCreateRejectedForPrice("15.00")
    }

    @Test
    fun `AC-3 (l) rejects price string 15 without fraction digits with 400`() {
        assertCreateRejectedForPrice("\"15\"")
    }

    @Test
    fun `AC-3 (m) rejects price string 15_5 with one fraction digit with 400`() {
        assertCreateRejectedForPrice("\"15.5\"")
    }

    @Test
    fun `AC-3 (n) rejects price string 15_000 with three fraction digits with 400`() {
        assertCreateRejectedForPrice("\"15.000\"")
    }

    @Test
    fun `AC-3 (o) rejects price string with a decimal comma with 400`() {
        assertCreateRejectedForPrice("\"15,00\"")
    }

    @Test
    fun `AC-3 (p) rejects a negative price string with 400`() {
        assertCreateRejectedForPrice("\"-1.00\"")
    }

    @Test
    fun `AC-3 (q) rejects malformed JSON with 400`() {
        assertCreateRejected("""{"name":"${uniqueName("Malformed")}","quantity":10,"price":"15.00"""")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4 .. AC-6: Idempotency-Key
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-4 replays the stored response for the same key and the same request`() {
        val key = newKey()
        val name = uniqueName("Replay")
        val request = """{"name":"$name","quantity":3,"price":"7.25"}"""

        val first = postGood(request, key)
        assertEquals(201, first.status.value(), "first status; body: ${body(first)}")
        val firstLocation = assertNotNull(first.responseHeaders.getFirst("Location"), "Location header missing")

        val second = postGood(request, key)

        assertEquals(201, second.status.value(), "second status; body: ${body(second)}")
        assertEquals("true", second.responseHeaders.getFirst(REPLAYED))
        assertEquals(firstLocation, second.responseHeaders.getFirst("Location"))
        assertEquals(json(first), json(second))
        assertEquals(1L, countGoodsNamed(name))
    }

    @Test
    fun `AC-5 rejects a key used by another endpoint with 422 and a request without key with 400`() {
        val key = newKey()
        val user = postJson(
            "/api/v1/users",
            """{"name":"Key Owner","email":"owner-${UUID.randomUUID()}@example.com"}""",
            key,
        )
        assertEquals(201, user.status.value(), "user status; body: ${body(user)}")

        val reusedName = uniqueName("Reused key")
        val reused = postGood("""{"name":"$reusedName","quantity":1,"price":"1.00"}""", key)

        assertProblem(reused, 422, "Idempotency key reused")
        assertEquals(0L, countGoodsNamed(reusedName))

        val noKeyName = uniqueName("No key")
        val noKey = postGood("""{"name":"$noKeyName","quantity":1,"price":"1.00"}""", key = null)

        assertProblem(noKey, 400, "Idempotency-Key header required")
        assertEquals(0L, countGoodsNamed(noKeyName))
    }

    @Test
    fun `AC-6 concurrent identical requests with the same key create at most one good`() {
        val key = newKey()
        val name = uniqueName("Concurrent")
        val request = """{"name":"$name","quantity":4,"price":"4.00"}"""
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<EntityExchangeResult<ByteArray>>()
        val errors = ConcurrentLinkedQueue<Throwable>()

        val threads = (1..CONCURRENT_REQUESTS).map {
            Thread.ofVirtual().start {
                try {
                    start.await()
                    results.add(postGood(request, key))
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
        assertEquals(1L, countGoodsNamed(name))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-7 .. AC-8: reads
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-7 reads a good by id and returns 404 problem detail for an unknown id`() {
        val created = postGood("""{"name":"${uniqueName("Readable")}","quantity":2,"price":"12.34"}""", newKey())
        assertEquals(201, created.status.value(), "status; body: ${body(created)}")
        val createdBody = json(created)

        val read = getGood(createdBody.path("id").asLong())

        assertEquals(200, read.status.value(), "status; body: ${body(read)}")
        assertEquals(createdBody, json(read))

        assertEquals(0L, count("SELECT count(*) FROM goods WHERE id = ?", UNKNOWN_ID))
        assertProblem(getGood(UNKNOWN_ID), 404)
    }

    @Test
    fun `AC-8 lists goods sorted by numeric price descending with pagination`() {
        val ownGoods = listOf(1000L, 900L, 300L, 200L, 100L).associateBy { uniqueName("Sorted ${formatCents(it)}") }
        // Inserted in a different order than expected in the result: 3.00, 1.00, 2.00, then 10.00 and 9.00.
        val insertOrder = listOf(300L, 100L, 200L, 1000L, 900L)
        insertOrder.forEach { cents ->
            val name = ownGoods.entries.single { it.value == cents }.key
            insertGood(name, quantity = 1, priceCents = cents)
        }

        val firstPage = getList("/api/v1/goods?sort=price,desc&size=100")

        assertEquals(200, firstPage.status.value(), "status; body: ${body(firstPage)}")
        val firstBody = json(firstPage)
        assertEquals(count("SELECT count(*) FROM goods"), firstBody.path("page").path("totalElements").asLong())
        val listed = contentOf(firstBody).toMutableList()
        val totalPages = firstBody.path("page").path("totalPages").asInt()
        for (page in 1 until totalPages) {
            val next = getList("/api/v1/goods?sort=price,desc&size=100&page=$page")
            assertEquals(200, next.status.value(), "status; body: ${body(next)}")
            listed += contentOf(json(next))
        }

        val listedPrices = listed.map { it.path("price").asString() }
        val listedNumeric = listedPrices.map { BigDecimal(it) }
        assertTrue(
            listedNumeric.zipWithNext().all { (a, b) -> a >= b },
            "prices must be in descending numeric order, were: $listedPrices",
        )
        val expectedPrices = jdbc.queryForList("SELECT price FROM goods ORDER BY price DESC", Long::class.javaObjectType)
            .map { formatCents(assertNotNull(it)) }
        assertEquals(expectedPrices, listedPrices)
        assertEquals(
            ownGoods.keys.toList(),
            listed.map { it.path("name").asString() }.filter { it in ownGoods.keys },
            "own goods must be ordered 10.00, 9.00, 3.00, 2.00, 1.00",
        )

        val secondPage = getList("/api/v1/goods?size=1&page=1")

        assertEquals(200, secondPage.status.value(), "status; body: ${body(secondPage)}")
        val secondBody = json(secondPage)
        assertEquals(1, secondBody.path("content").size())
        assertEquals(1, secondBody.path("page").path("number").asInt())

        assertProblem(getList("/api/v1/goods?sort=unknownField,asc"), 400)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-9 .. AC-12: partial updates
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-9 updates quantity without an Idempotency-Key, then name and price`() {
        val name = uniqueName("Mug")
        val id = insertGood(name, quantity = 10, priceCents = 1500)

        val quantityUpdate = patchGood(id, """{"quantity":25}""")

        assertEquals(200, quantityUpdate.status.value(), "status; body: ${body(quantityUpdate)}")
        val response = json(quantityUpdate)
        assertEquals(25L, response.path("quantity").asLong())
        assertEquals(name, response.path("name").asString())
        assertEquals("15.00", response.path("price").asString())
        val createdAt = Instant.parse(response.path("createdAt").asString())
        assertEquals(Instant.parse(CREATED_AT), createdAt)
        assertIsoUtcTimestamp(response.path("updatedAt"))
        val updatedAt = Instant.parse(response.path("updatedAt").asString())
        assertTrue(!updatedAt.isBefore(createdAt), "updatedAt $updatedAt must not be before createdAt $createdAt")
        assertEquals(25L, longValue(goodRow(id)["quantity"]))

        val namePriceUpdate = patchGood(id, """{"name":" Big mug ","price":"25.00"}""")

        assertEquals(200, namePriceUpdate.status.value(), "status; body: ${body(namePriceUpdate)}")
        val updated = json(namePriceUpdate)
        assertEquals("Big mug", updated.path("name").asString())
        assertEquals("25.00", updated.path("price").asString())
        val row = goodRow(id)
        assertEquals("Big mug", row["name"])
        assertEquals(2500L, longValue(row["price"]))
    }

    @Test
    fun `AC-10 repeating the same PATCH returns the same 200 body and leaves the row unchanged`() {
        val id = insertGood(uniqueName("Repeat"), quantity = 10, priceCents = 1500)
        val request = """{"name":" ${uniqueName("Repeated")} ","quantity":7,"price":"9.99"}"""

        val first = patchGood(id, request)
        assertEquals(200, first.status.value(), "first status; body: ${body(first)}")
        val rowAfterFirst = goodRow(id)

        val second = patchGood(id, request)

        assertEquals(200, second.status.value(), "second status; body: ${body(second)}")
        assertEquals(json(first), json(second))
        assertEquals(rowAfterFirst, goodRow(id))
    }

    @Test
    fun `AC-11 (a) rejects an empty object with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{}""")
    }

    @Test
    fun `AC-11 (b) rejects null quantity as the only field with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"quantity":null}""")
    }

    @Test
    fun `AC-11 (c) rejects quantity -1 with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"quantity":-1}""")
    }

    @Test
    fun `AC-11 (d) rejects price 0_00 with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"price":"0.00"}""")
    }

    @Test
    fun `AC-11 (e) rejects price as a JSON number with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"price":2500}""")
    }

    @Test
    fun `AC-11 (f) rejects price string 25 without fraction digits with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"price":"25"}""")
    }

    @Test
    fun `AC-11 (g) rejects an empty name with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"name":""}""")
    }

    @Test
    fun `AC-11 (h) rejects malformed JSON with 400 and leaves the row unchanged`() {
        assertPatchRejected("""{"quantity":""")
    }

    @Test
    fun `AC-12 returns 404 problem detail when patching an unknown good`() {
        assertEquals(0L, count("SELECT count(*) FROM goods WHERE id = ?", UNKNOWN_ID))

        val result = patchGood(UNKNOWN_ID, """{"quantity":1}""")

        assertProblem(result, 404)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-13: exact money conversion
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-13 stores and returns prices exactly as integer minor units`() {
        val cases = listOf("0.29" to 29L, "0.57" to 57L, "1.15" to 115L, "9999999.99" to 999999999L)
        val ids = cases.map { (price, cents) ->
            val result = postGood("""{"name":"${uniqueName("Exact $price")}","quantity":1,"price":"$price"}""", newKey())
            assertEquals(201, result.status.value(), "status for $price; body: ${body(result)}")
            val response = json(result)
            assertEquals(price, response.path("price").asString())
            val id = response.path("id").asLong()
            assertPriceRow(id, cents)
            id
        }

        val target = ids.last()
        val patched = patchGood(target, """{"price":"0.29"}""")

        assertEquals(200, patched.status.value(), "status; body: ${body(patched)}")
        assertEquals("0.29", json(patched).path("price").asString())
        val read = getGood(target)
        assertEquals(200, read.status.value(), "status; body: ${body(read)}")
        assertEquals("0.29", json(read).path("price").asString())
        assertPriceRow(target, 29L)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-14: name length counts Unicode code points
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `AC-14 accepts a name of 200 emoji and rejects 201 emoji on create and on patch`() {
        val name200 = EMOJI.repeat(200)
        assertEquals(200, codePoints(name200))
        val name201 = EMOJI.repeat(201)
        assertEquals(201, codePoints(name201))

        val created = postGood("""{"name":"$name200","quantity":1,"price":"1.00"}""", newKey())

        assertEquals(201, created.status.value(), "status; body: ${body(created)}")
        val response = json(created)
        assertEquals(name200, response.path("name").asString())
        val id = response.path("id").asLong()
        assertEquals(200L, count("SELECT length(name) FROM goods WHERE id = ?", id))

        val goodsBefore = count("SELECT count(*) FROM goods")
        val rejected = postGood("""{"name":"$name201","quantity":1,"price":"1.00"}""", newKey())

        assertProblem(rejected, 400)
        assertEquals(goodsBefore, count("SELECT count(*) FROM goods"), "goods row count changed")

        val rowBefore = goodRow(id)
        val rejectedPatch = patchGood(id, """{"name":"$name201"}""")

        assertProblem(rejectedPatch, 400)
        assertEquals(rowBefore, goodRow(id), "goods row changed")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private fun postGood(body: String, key: String?): EntityExchangeResult<ByteArray> =
        postJson("/api/v1/goods", body, key)

    private fun postJson(path: String, body: String, key: String?): EntityExchangeResult<ByteArray> =
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> if (key != null) headers.set(IDEMPOTENCY_KEY, key) }
            .body(body.toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()

    private fun patchGood(id: Long, body: String): EntityExchangeResult<ByteArray> =
        client.patch().uri("/api/v1/goods/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body.toByteArray(Charsets.UTF_8))
            .exchange()
            .expectBody()
            .returnResult()

    private fun getGood(id: Long): EntityExchangeResult<ByteArray> =
        client.get().uri("/api/v1/goods/{id}", id)
            .exchange()
            .expectBody()
            .returnResult()

    private fun getList(uri: String): EntityExchangeResult<ByteArray> =
        client.get().uri(uri)
            .exchange()
            .expectBody()
            .returnResult()

    private fun assertCreateRejected(body: String) {
        val goodsBefore = count("SELECT count(*) FROM goods")

        val result = postGood(body, newKey())

        assertProblem(result, 400)
        assertEquals(goodsBefore, count("SELECT count(*) FROM goods"), "goods row count changed")
    }

    /** A create request that is valid except for [priceJson], the raw JSON value of `price`. */
    private fun assertCreateRejectedForPrice(priceJson: String) {
        assertCreateRejected("""{"name":"${uniqueName("Bad price")}","quantity":10,"price":$priceJson}""")
    }

    private fun assertPatchRejected(body: String) {
        val id = insertGood(uniqueName("Unchanged"), quantity = 10, priceCents = 1500)
        val rowBefore = goodRow(id)

        val result = patchGood(id, body)

        assertProblem(result, 400)
        assertEquals(rowBefore, goodRow(id), "goods row changed")
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

    private fun assertPriceRow(id: Long, cents: Long) {
        val row = jdbc.queryForMap("SELECT price, typeof(price) AS price_type FROM goods WHERE id = ?", id)
        assertEquals(cents, longValue(row["price"]))
        assertEquals("integer", row["price_type"])
    }

    private fun assertIsoUtcTimestamp(node: JsonNode) {
        assertTrue(node.isString, "timestamp must be a JSON string, was: $node")
        val text = node.asString()
        assertTrue(ISO_UTC.matches(text), "timestamp must be ISO-8601 UTC ending in Z, was: $text")
        Instant.parse(text)
    }

    /** Inserts a good directly with SQL and returns its ID. [name] must be unique. */
    private fun insertGood(name: String, quantity: Long, priceCents: Long): Long {
        jdbc.update(
            "INSERT INTO goods (name, quantity, price, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            name, quantity, priceCents, CREATED_AT, CREATED_AT,
        )
        return assertNotNull(jdbc.queryForObject("SELECT id FROM goods WHERE name = ?", Long::class.javaObjectType, name))
    }

    private fun goodRow(id: Long): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM goods WHERE id = ?", id)

    private fun countGoodsNamed(name: String): Long = count("SELECT count(*) FROM goods WHERE name = ?", name)

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L

    private fun contentOf(page: JsonNode): List<JsonNode> {
        val content = page.path("content")
        assertTrue(content.isArray, "content must be an array, was: $content")
        return (0 until content.size()).map { content.path(it) }
    }

    private fun json(result: EntityExchangeResult<ByteArray>): JsonNode = mapper.readTree(result.responseBodyContent)

    private fun body(result: EntityExchangeResult<ByteArray>): String =
        result.responseBodyContent?.toString(Charsets.UTF_8) ?: ""

    private fun longValue(value: Any?): Long = (assertNotNull(value) as Number).toLong()

    private fun formatCents(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    private fun newKey(): String = UUID.randomUUID().toString()

    private fun uniqueName(prefix: String): String = "$prefix ${UUID.randomUUID()}"

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val REPLAYED = "Idempotent-Replayed"
        const val CONCURRENT_REQUESTS = 10
        const val UNKNOWN_ID = Long.MAX_VALUE
        const val CREATED_AT = "2026-11-08T12:00:00.123Z"
        val ISO_UTC = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")

        /** U+1F600: one code point, two UTF-16 code units, four UTF-8 bytes. */
        val EMOJI = String(Character.toChars(0x1F600))
    }
}
