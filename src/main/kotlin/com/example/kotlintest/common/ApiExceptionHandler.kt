package com.example.kotlintest.common

import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * The single place that maps exceptions to RFC 9457 `ProblemDetail` responses.
 *
 * Spring MVC exceptions (validation, malformed JSON, type mismatch, unknown path, ...) are handled by
 * [ResponseEntityExceptionHandler]. Because this class extends it, Boot's own `ProblemDetailsExceptionHandler` backs off.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatus(ex.status)
        problem.title = ex.title
        problem.detail = ex.message
        return ResponseEntity.status(ex.status).body(problem)
    }

    /** `409`: an update lost an optimistic-locking race (`@Version`). The client can reload and retry. */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLocking(ex: OptimisticLockingFailureException): ResponseEntity<ProblemDetail> {
        log.debug("Optimistic locking failure", ex)
        val problem = ProblemDetail.forStatus(HttpStatus.CONFLICT)
        problem.title = "Concurrent modification"
        problem.detail = "The resource was modified concurrently. Reload it and try again."
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }

    /** Last resort: never leak messages, stack traces or SQL to the client. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled exception", ex)
        val problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        problem.detail = "An unexpected error occurred."
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }
}
