package com.example.kotlintest.common.idempotency

import com.example.kotlintest.common.isUniqueViolation
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant

/**
 * Runs the operation of an idempotent `POST` endpoint and stores its response under the request's `Idempotency-Key`
 * in the **same database transaction** as the business change.
 *
 * Usage in a controller (the request must have passed [IdempotencyInterceptor]):
 * ```
 * @PostMapping
 * fun create(@Valid @RequestBody body: CreateThingRequest, request: HttpServletRequest) =
 *     idempotentExecutor.execute(request) {
 *         val thing = thingService.create(...)
 *         ResponseEntity.created(URI.create("/api/v1/things/${thing.id}")).body(ThingResponse.from(thing))
 *     }
 * ```
 *
 * - The record is inserted **first**, so the transaction is a write transaction from its first statement. This
 *   avoids SQLite's read-then-write `SQLITE_BUSY_SNAPSHOT` failures, and `busy_timeout` applies to the wait.
 * - Only `2xx` responses are recorded. If the operation throws, everything is rolled back and no record remains,
 *   so the client can retry with the same key.
 * - A `UNIQUE` violation on the key (another JVM stored it meanwhile) becomes `409 Request in progress`.
 */
@Component
class IdempotentExecutor(
    private val records: IdempotencyRecordRepository,
    private val transactionTemplate: TransactionTemplate,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun <T : Any> execute(request: HttpServletRequest, operation: () -> ResponseEntity<T>): ResponseEntity<T> {
        val context = request.getAttribute(IdempotencyContext.ATTRIBUTE) as? IdempotencyContext
            ?: error("No idempotency context for ${request.method} ${request.requestURI}; is IdempotencyInterceptor registered?")

        val result = transactionTemplate.execute {
            val reserved = reserve(context)
            val response = operation()
            check(response.statusCode.is2xxSuccessful) {
                "Only 2xx responses can be recorded, was ${response.statusCode}"
            }
            records.save(
                reserved.copy(
                    responseStatus = response.statusCode.value(),
                    responseLocation = response.headers.location?.toString(),
                    responseBody = response.body?.let { jsonMapper.writeValueAsString(it) } ?: "",
                ),
            )
            response
        }
        return checkNotNull(result) { "Transaction returned no response" }
    }

    private fun reserve(context: IdempotencyContext): IdempotencyRecord = try {
        records.save(
            IdempotencyRecord(
                idempotencyKey = context.key,
                requestMethod = context.method,
                requestPath = context.path,
                requestHash = context.bodyHash,
                responseStatus = PENDING_STATUS,
                responseLocation = null,
                responseBody = "",
                createdAt = Instant.now(clock),
            ),
        )
    } catch (e: DataAccessException) {
        if (e.isUniqueViolation("idempotency_keys.idempotency_key")) throw RequestInProgressException()
        throw e
    }

    private companion object {
        /** Placeholder status of a reserved record. It is replaced before the transaction commits. */
        const val PENDING_STATUS = 0
    }
}
