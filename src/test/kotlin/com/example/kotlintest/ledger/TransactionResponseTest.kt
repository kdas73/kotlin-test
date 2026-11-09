package com.example.kotlintest.ledger

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** Implementer's unit tests for [TransactionResponse] (STORY-004, not locked). */
class TransactionResponseTest {

    @Test
    fun `money is formatted as strings and entries are ordered by amount ascending`() {
        val transaction = Transaction(
            id = 10,
            type = TransactionType.DEPOSIT,
            amount = 29,
            createdAt = Instant.parse("2026-11-09T10:00:00.123Z"),
            entries = linkedSetOf(
                TransactionEntry(id = 2, accountId = 7, amount = 29),
                TransactionEntry(id = 1, accountId = 1, amount = -29),
            ),
        )

        val response = TransactionResponse.from(transaction)

        assertEquals(10L, response.id)
        assertEquals(TransactionType.DEPOSIT, response.type)
        assertEquals("0.29", response.amount)
        assertEquals(
            listOf(TransactionEntryResponse(1, "-0.29"), TransactionEntryResponse(7, "0.29")),
            response.entries,
        )
    }
}
