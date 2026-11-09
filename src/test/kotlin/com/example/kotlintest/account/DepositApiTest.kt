package com.example.kotlintest.account

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Implementer's additional API checks for STORY-004 (not locked). Deposits go only through the API, so the ledger in
 * the shared test database stays balanced.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class DepositApiTest(
    @Autowired private val client: RestTestClient,
) {

    @Test
    fun `an unknown transaction returns 404 titled Transaction not found`() {
        client.get().uri("/api/v1/transactions/{id}", Long.MAX_VALUE)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.title").isEqualTo("Transaction not found")
    }

    @Test
    fun `a non-numeric transaction id returns a 400 problem detail`() {
        client.get().uri("/api/v1/transactions/abc")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `a deposit to an unknown user returns 404 titled User not found`() {
        deposit(Long.MAX_VALUE, "1.00", newKey())
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.title").isEqualTo("User not found")
    }

    @Test
    fun `reusing a key with a different amount returns 422 and changes nothing`() {
        val userId = newUser()
        val key = newKey()
        deposit(userId, "1.00", key).expectStatus().isCreated

        deposit(userId, "2.00", key)
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.title").isEqualTo("Idempotency key reused")
        client.get().uri("/api/v1/users/{id}/account", userId)
            .exchange()
            .expectBody()
            .jsonPath("$.balance").isEqualTo("1.00")
    }

    @Test
    fun `a rejected deposit is not recorded, so its key can be used for a valid deposit`() {
        val userId = newUser()
        val key = newKey()
        deposit(userId, "0.00", key).expectStatus().isBadRequest

        deposit(userId, "3.00", key).expectStatus().isCreated
    }

    private fun deposit(userId: Long, amount: String, key: String) =
        client.post().uri("/api/v1/users/{id}/account/deposits", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key)
            .body("""{"amount":"$amount"}""")
            .exchange()

    private fun newUser(): Long {
        val body = client.post().uri("/api/v1/users")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", newKey())
            .body("""{"name":"Depositor","email":"deposit-api-${UUID.randomUUID()}@example.com"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()
            .responseBodyContent
        return JsonMapper.shared().readTree(body).path("id").asLong()
    }

    private fun newKey() = UUID.randomUUID().toString()
}
