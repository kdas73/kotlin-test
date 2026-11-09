package com.example.kotlintest.common

import org.springframework.http.HttpStatus

/**
 * Base class for domain errors. [ApiExceptionHandler] maps every subclass to an RFC 9457 `ProblemDetail` with the
 * given [status], [title] and the exception message as `detail`.
 */
abstract class ApiException(
    val status: HttpStatus,
    val title: String,
    detail: String,
) : RuntimeException(detail)

/** `400`: a sort property that the endpoint does not support. */
class InvalidSortPropertyException(property: String, allowed: Collection<String>) : ApiException(
    HttpStatus.BAD_REQUEST,
    "Invalid sort property",
    "Unknown sort property '$property'. Allowed: ${allowed.joinToString(", ")}.",
)
