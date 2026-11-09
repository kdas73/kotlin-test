package com.example.kotlintest.common

import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Implementer's tests for [isSqliteLockConflict] and its `409` mapping (STORY-004, not locked). */
class SqliteErrorsTest {

    private val handler = ApiExceptionHandler()

    @Test
    fun `SQLITE_BUSY and SQLITE_LOCKED anywhere in the cause chain are lock conflicts`() {
        assertTrue(sqlError(errorCode = 5).isSqliteLockConflict())
        assertTrue(sqlError(errorCode = 6).isSqliteLockConflict())
        assertTrue(CannotCreateTransactionException("begin", SQLException("busy", null, 5)).isSqliteLockConflict())
    }

    @Test
    fun `other SQLite errors are not lock conflicts`() {
        assertFalse(sqlError(errorCode = 19).isSqliteLockConflict())
        assertFalse(sqlError(errorCode = 1).isSqliteLockConflict())
        assertFalse(IllegalStateException("no SQL").isSqliteLockConflict())
    }

    @Test
    fun `a lock conflict is a 409 titled Concurrent modification`() {
        listOf(sqlError(errorCode = 5), sqlError(errorCode = 6)).forEach { ex ->
            val response = handler.handleDatabaseError(ex)

            assertEquals(409, response.statusCode.value())
            assertEquals("Concurrent modification", response.body?.title)
        }
    }

    @Test
    fun `any other database error is a 500 without details`() {
        val response = handler.handleDatabaseError(DataIntegrityViolationException("CHECK constraint failed: secret"))

        assertEquals(500, response.statusCode.value())
        assertEquals("An unexpected error occurred.", response.body?.detail)
    }

    private fun sqlError(errorCode: Int) =
        UncategorizedSQLException("PreparedStatementCallback", "INSERT ...", SQLException("SQLite error", null, errorCode))
}
