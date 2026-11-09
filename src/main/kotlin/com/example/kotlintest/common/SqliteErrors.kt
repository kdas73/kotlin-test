package com.example.kotlintest.common

import java.sql.SQLException

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
