package com.example.kotlintest.common.idempotency

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant

/** The stored successful response of an idempotent POST request (table `idempotency_keys`). */
@Table("idempotency_keys")
data class IdempotencyRecord(
    @Id val id: Long? = null,
    val idempotencyKey: String,
    val requestMethod: String,
    val requestPath: String,
    val requestHash: String,
    val responseStatus: Int,
    val responseLocation: String?,
    val responseBody: String,
    val createdAt: Instant,
)

interface IdempotencyRecordRepository : ListCrudRepository<IdempotencyRecord, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): IdempotencyRecord?
}
