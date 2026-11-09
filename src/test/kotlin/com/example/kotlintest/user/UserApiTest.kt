package com.example.kotlintest.user

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/** Implementer's additional API checks for STORY-001 (not locked). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class UserApiTest(@Autowired private val client: RestTestClient) {

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
}
