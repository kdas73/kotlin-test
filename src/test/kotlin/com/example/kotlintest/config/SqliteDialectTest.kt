package com.example.kotlintest.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteDialectTest {

    private val limit = SqliteDialect.limit()

    @Test
    fun `renders SQLite paging clauses`() {
        assertEquals("LIMIT 10", limit.getLimit(10))
        assertEquals("LIMIT -1 OFFSET 20", limit.getOffset(20))
        assertEquals("LIMIT 10 OFFSET 20", limit.getLimitOffset(10, 20))
    }
}
