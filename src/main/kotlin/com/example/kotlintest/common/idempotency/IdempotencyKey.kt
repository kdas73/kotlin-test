package com.example.kotlintest.common.idempotency

import java.security.MessageDigest
import java.util.HexFormat

/** Rules for the `Idempotency-Key` header and the request fingerprint. */
object IdempotencyKey {

    const val HEADER = "Idempotency-Key"
    const val REPLAYED_HEADER = "Idempotent-Replayed"
    const val MAX_LENGTH = 255

    /**
     * Validates a raw header value and returns the key.
     *
     * @throws IdempotencyKeyRequiredException if the header is missing or blank
     * @throws InvalidIdempotencyKeyException if it is longer than [MAX_LENGTH] or has a character outside `0x21..0x7E`
     */
    fun validate(headerValue: String?): String {
        if (headerValue.isNullOrBlank()) throw IdempotencyKeyRequiredException()
        if (headerValue.length > MAX_LENGTH || headerValue.any { it !in '!'..'~' }) {
            throw InvalidIdempotencyKeyException()
        }
        return headerValue
    }

    /** SHA-256 hex digest of the raw request body bytes (an empty body hashes the empty input). */
    fun bodyHash(body: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
}

/** The idempotency data of a POST request that is allowed to run. Stored as a request attribute. */
data class IdempotencyContext(
    val key: String,
    val method: String,
    val path: String,
    val bodyHash: String,
) {
    fun matches(record: IdempotencyRecord): Boolean =
        record.requestMethod == method && record.requestPath == path && record.requestHash == bodyHash

    companion object {
        val ATTRIBUTE: String = IdempotencyContext::class.java.name
    }
}
