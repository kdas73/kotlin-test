package com.example.kotlintest.common

import java.sql.SQLException

/** SQLite primary result code `SQLITE_BUSY`: the database file is locked by another connection. */
private const val SQLITE_BUSY = 5

/** SQLite primary result code `SQLITE_LOCKED`: a table is locked by another connection of the same shared cache. */
private const val SQLITE_LOCKED = 6

/** SQLite primary result code `SQLITE_CONSTRAINT`. The xerial driver reports it as [SQLException.getErrorCode]. */
private const val SQLITE_CONSTRAINT = 19

/**
 * True if this exception (or one of its causes) is a SQLite `UNIQUE` constraint violation on [tableDotColumn]
 * (for example `users.email`).
 */
fun Throwable.isUniqueViolation(tableDotColumn: String): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is SQLException &&
            cause.errorCode == SQLITE_CONSTRAINT &&
            cause.message.orEmpty().contains("UNIQUE constraint failed: $tableDotColumn")
    }

/**
 * True if this exception (or one of its causes) means that a concurrent database transaction held a conflicting lock:
 * `SQLITE_BUSY` (the write lock was still held when `busy_timeout` ran out) or `SQLITE_LOCKED` (shared-cache table
 * lock, as in the in-memory test database). The failed statement had no effect, so the request can be retried.
 */
fun Throwable.isSqliteLockConflict(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is SQLException && (cause.errorCode == SQLITE_BUSY || cause.errorCode == SQLITE_LOCKED)
    }
