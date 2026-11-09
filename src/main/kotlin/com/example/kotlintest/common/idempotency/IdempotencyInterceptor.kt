package com.example.kotlintest.common.idempotency

import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletRequestWrapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Applies the `Idempotency-Key` rules to every `POST` endpoint under `/api/v1`, before request validation:
 *
 * 1. header missing or blank -> `400 Idempotency-Key header required`;
 * 2. header too long or with forbidden characters -> `400 Invalid Idempotency-Key header`;
 * 3. another request with the same key is running -> `409 Request in progress`;
 * 4. a stored record with a different fingerprint -> `422 Idempotency key reused`;
 * 5. a stored record with the same fingerprint -> replay of the stored response with `Idempotent-Replayed: true`;
 * 6. otherwise the request runs; the controller wraps its operation in [IdempotentExecutor].
 *
 * Exceptions thrown here are rendered as `ProblemDetail`s by the `@RestControllerAdvice`.
 */
@Component
class IdempotencyInterceptor(
    private val records: IdempotencyRecordRepository,
    private val locks: IdempotencyKeyLocks,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method != HttpMethod.POST.name() || handler !is HandlerMethod) return true

        val key = IdempotencyKey.validate(request.getHeader(IdempotencyKey.HEADER))
        val body = findCachedBody(request)
            ?: error("CachedBodyFilter did not wrap POST ${request.requestURI}")
        val context = IdempotencyContext(
            key = key,
            method = request.method,
            path = request.pathWithinApplication(),
            bodyHash = IdempotencyKey.bodyHash(body),
        )

        if (!locks.tryAcquire(key)) throw RequestInProgressException()
        var proceed = false
        try {
            val record = records.findByIdempotencyKey(key)
            if (record != null) {
                if (!context.matches(record)) throw IdempotencyKeyReusedException()
                replay(record, response)
                return false
            }
            request.setAttribute(IdempotencyContext.ATTRIBUTE, context)
            proceed = true
            return true
        } finally {
            if (!proceed) locks.release(key)
        }
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val context = request.getAttribute(IdempotencyContext.ATTRIBUTE) as? IdempotencyContext ?: return
        locks.release(context.key)
    }

    private fun replay(record: IdempotencyRecord, response: HttpServletResponse) {
        response.status = record.responseStatus
        record.responseLocation?.let { response.setHeader(HttpHeaders.LOCATION, it) }
        response.setHeader(IdempotencyKey.REPLAYED_HEADER, "true")
        if (record.responseBody.isNotEmpty()) {
            val bytes = record.responseBody.toByteArray(Charsets.UTF_8)
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.setContentLength(bytes.size)
            response.outputStream.write(bytes)
        }
        response.flushBuffer()
    }

    private tailrec fun findCachedBody(request: ServletRequest): ByteArray? = when (request) {
        is CachedBodyHttpServletRequest -> request.cachedBody
        is ServletRequestWrapper -> findCachedBody(request.request)
        else -> null
    }
}

@Component
class IdempotencyWebConfig(private val interceptor: IdempotencyInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**")
    }
}
