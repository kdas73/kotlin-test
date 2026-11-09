package com.example.kotlintest.good

import com.example.kotlintest.common.ApiExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID
import kotlin.test.assertEquals

/** Implementer's additional API checks for STORY-003 (not locked). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class GoodApiTest(
    @Autowired private val client: RestTestClient,
) {

    @Test
    fun `every allowed sort property is accepted`() {
        listOf("id", "name", "quantity", "price", "createdAt").forEach { property ->
            client.get().uri("/api/v1/goods?sort=$property,desc")
                .exchange()
                .expectStatus().isOk
        }
    }

    @Test
    fun `unknown sort property returns 400 titled Invalid sort property`() {
        client.get().uri("/api/v1/goods?sort=version,asc")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.title").isEqualTo("Invalid sort property")
    }

    @Test
    fun `a non-integer quantity is rejected on create and on patch`() {
        listOf("10.5", "\"10\"", "2147483648").forEach { quantity ->
            client.post().uri("/api/v1/goods")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""{"name":"Strict ${UUID.randomUUID()}","quantity":$quantity,"price":"1.00"}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        }
    }

    @Test
    fun `a non-numeric good id returns a 400 problem detail`() {
        client.get().uri("/api/v1/goods/abc")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `an unknown good returns 404 titled Good not found on GET and PATCH`() {
        client.get().uri("/api/v1/goods/{id}", Long.MAX_VALUE)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.title").isEqualTo("Good not found")
        client.patch().uri("/api/v1/goods/{id}", Long.MAX_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"quantity":1}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.title").isEqualTo("Good not found")
    }

    @Test
    fun `an optimistic locking failure is a 409 titled Concurrent modification`() {
        val response = ApiExceptionHandler().handleOptimisticLocking(OptimisticLockingFailureException("lost race"))

        assertEquals(409, response.statusCode.value())
        val problem = response.body
        assertEquals("Concurrent modification", problem?.title)
        assertEquals(409, problem?.status)
    }
}
