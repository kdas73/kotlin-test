package com.example.kotlintest.common.idempotency

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Reads the body of every `POST /api/v1/...` request once and makes it re-readable, so [IdempotencyInterceptor] can
 * hash the raw bytes before the message converter reads them.
 */
@Component
class CachedBodyFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || !request.pathWithinApplication().startsWith(API_PREFIX)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        chain.doFilter(CachedBodyHttpServletRequest(request, request.inputStream.readAllBytes()), response)
    }

    private companion object {
        const val API_PREFIX = "/api/v1/"
    }
}

internal fun HttpServletRequest.pathWithinApplication(): String = requestURI.removePrefix(contextPath)

/** A request whose body was read into memory and can be read any number of times. */
class CachedBodyHttpServletRequest(
    request: HttpServletRequest,
    val cachedBody: ByteArray,
) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream = CachedBodyInputStream(cachedBody)

    override fun getReader(): BufferedReader {
        val charset = characterEncoding?.let { Charset.forName(it) } ?: Charsets.UTF_8
        return BufferedReader(InputStreamReader(ByteArrayInputStream(cachedBody), charset))
    }

    private class CachedBodyInputStream(body: ByteArray) : ServletInputStream() {
        private val delegate = ByteArrayInputStream(body)

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun isFinished(): Boolean = delegate.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener) {
            throw UnsupportedOperationException("Async reads are not supported")
        }
    }
}
