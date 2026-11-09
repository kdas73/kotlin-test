package com.example.kotlintest.user

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID
import kotlin.test.assertEquals

/** Implementer's additional API checks for STORY-001 and STORY-002 (not locked). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class UserApiTest(
    @Autowired private val client: RestTestClient,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `unknown sort property returns a 400 problem detail`() {
        client.get().uri("/api/v1/users?sort=unknownField,asc")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.title").isEqualTo("Invalid sort property")
    }

    @Test
    fun `every allowed sort property is accepted`() {
        listOf("id", "name", "email", "createdAt").forEach { property ->
            client.get().uri("/api/v1/users?sort=$property,desc")
                .exchange()
                .expectStatus().isOk
        }
    }

    @Test
    fun `a non-numeric user id returns a 400 problem detail`() {
        client.get().uri("/api/v1/users/abc")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `an unknown user returns a 404 problem detail titled User not found`() {
        client.get().uri("/api/v1/users/{id}", Long.MAX_VALUE)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.title").isEqualTo("User not found")
    }

    @Test
    fun `GET ignores an Idempotency-Key header`() {
        client.get().uri("/api/v1/users")
            .header("Idempotency-Key", "not a valid key")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `a replayed response is served with application json`() {
        val key = UUID.randomUUID().toString()
        val body = """{"name":"Eve","email":"eve-$key@example.com"}"""
        repeat(2) {
            client.post().uri("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(body)
                .exchange()
                .expectStatus().isCreated
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        }
    }

    @Test
    fun `PATCH ignores an Idempotency-Key header and records nothing`() {
        val id = insertUser("Henry")
        val keysBefore = countIdempotencyKeys()

        client.patch().uri("/api/v1/users/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "not a valid key")
            .body("""{"name":"Henry Ford"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("Henry Ford")

        assertEquals(keysBefore, countIdempotencyKeys())
    }

    @Test
    fun `PATCH with an email longer than 254 code points returns a 400 problem detail`() {
        val id = insertUser("Ivy")
        val email = "a".repeat(243) + "@example.com" // 255 code points

        client.patch().uri("/api/v1/users/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"email":"$email"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `PATCH with a non-numeric user id returns a 400 problem detail`() {
        client.patch().uri("/api/v1/users/abc")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"name":"Jack"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `PATCH of an unknown user returns a 404 problem detail titled User not found`() {
        client.patch().uri("/api/v1/users/{id}", Long.MAX_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"name":"Kate"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.title").isEqualTo("User not found")
    }

    private fun insertUser(name: String): Long {
        val email = "${name.lowercase()}-${UUID.randomUUID()}@example.com"
        jdbc.update(
            "INSERT INTO users (name, email, created_at) VALUES (?, ?, ?)",
            name, email, "2026-11-08T12:00:00.123Z",
        )
        return checkNotNull(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long::class.javaObjectType, email))
    }

    private fun countIdempotencyKeys(): Long =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long::class.javaObjectType))
}
