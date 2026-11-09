package com.example.kotlintest.common.idempotency

import com.example.kotlintest.common.ApiException
import org.springframework.http.HttpStatus

/** `400`: the `Idempotency-Key` header is missing or blank. */
class IdempotencyKeyRequiredException : ApiException(
    HttpStatus.BAD_REQUEST,
    "Idempotency-Key header required",
    "Every POST request must have an Idempotency-Key header.",
)

/** `400`: the `Idempotency-Key` header is too long or contains forbidden characters. */
class InvalidIdempotencyKeyException : ApiException(
    HttpStatus.BAD_REQUEST,
    "Invalid Idempotency-Key header",
    "The Idempotency-Key header must be 1-${IdempotencyKey.MAX_LENGTH} visible ASCII characters (0x21-0x7E).",
)

/** `422`: the key was already used for a different request (method, path or body). */
class IdempotencyKeyReusedException : ApiException(
    HttpStatus.UNPROCESSABLE_CONTENT,
    "Idempotency key reused",
    "This Idempotency-Key was already used for a different request.",
)

/** `409`: another request with the same key is still being processed. */
class RequestInProgressException : ApiException(
    HttpStatus.CONFLICT,
    "Request in progress",
    "A request with this Idempotency-Key is still being processed. Retry later.",
)
