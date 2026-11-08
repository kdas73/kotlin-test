package com.example.kotlintest.config

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/** Guards `spring.threads.virtual.enabled=true`: HTTP requests must be handled on virtual threads. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(VirtualThreadsTest.ThreadProbe::class)
class VirtualThreadsTest {

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `requests are handled on virtual threads`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/test/thread")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals("true", response.body())
    }

    @TestConfiguration
    @RestController
    class ThreadProbe {
        @GetMapping("/test/thread")
        fun isVirtual(): String = Thread.currentThread().isVirtual.toString()
    }
}
