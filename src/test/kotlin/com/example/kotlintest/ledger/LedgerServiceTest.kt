package com.example.kotlintest.ledger

import com.example.kotlintest.account.Account
import com.example.kotlintest.account.AccountKind
import com.example.kotlintest.account.AccountRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Implementer's unit tests for [LedgerService] (STORY-004, not locked). */
class LedgerServiceTest {

    private val accounts = mock(AccountRepository::class.java)
    private val transactions = mock(TransactionRepository::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00.123456Z"), ZoneOffset.UTC)
    private val service = LedgerService(accounts, transactions, clock)

    private val funding = Account(
        id = 1,
        userId = null,
        kind = AccountKind.FUNDING,
        balance = -10_000,
        version = 5,
        createdAt = Instant.parse(CREATED_AT),
    )
    private val user = Account(
        id = 7,
        userId = 3,
        kind = AccountKind.USER,
        balance = 2_500,
        version = 2,
        createdAt = Instant.parse(CREATED_AT),
    )

    @Test
    fun `post moves the amount between the accounts with their loaded versions and records balanced entries`() {
        val expected = Transaction(
            type = TransactionType.DEPOSIT,
            amount = 5_000,
            createdAt = Instant.parse("2026-11-09T10:00:00.123Z"),
            entries = setOf(
                TransactionEntry(accountId = 1, amount = -5_000),
                TransactionEntry(accountId = 7, amount = 5_000),
            ),
        )
        `when`(transactions.save(expected)).thenReturn(expected.copy(id = 10))

        val result = service.post(TransactionType.DEPOSIT, from = funding, to = user, amount = 5_000)

        assertEquals(10L, result.id)
        verify(accounts).save(funding.copy(balance = -15_000))
        verify(accounts).save(user.copy(balance = 7_500))
    }

    @Test
    fun `post rejects a non-positive amount without writing`() {
        listOf(0L, -1L).forEach { amount ->
            assertFailsWith<IllegalArgumentException> {
                service.post(TransactionType.DEPOSIT, from = funding, to = user, amount = amount)
            }
        }
        verifyNoInteractions(accounts, transactions)
    }

    @Test
    fun `post rejects a transaction from an account to itself`() {
        assertFailsWith<IllegalArgumentException> {
            service.post(TransactionType.DEPOSIT, from = user, to = user, amount = 100)
        }
        verifyNoInteractions(accounts, transactions)
    }

    @Test
    fun `post fails on a balance overflow instead of wrapping around`() {
        val full = user.copy(balance = Long.MAX_VALUE)

        assertFailsWith<ArithmeticException> {
            service.post(TransactionType.DEPOSIT, from = funding, to = full, amount = 1)
        }
        verify(transactions, never()).save(any<Transaction>())
    }

    @Test
    fun `get throws TransactionNotFoundException for an unknown id`() {
        `when`(transactions.findById(99)).thenReturn(Optional.empty())

        val ex = assertFailsWith<TransactionNotFoundException> { service.get(99) }

        assertEquals("Transaction not found", ex.title)
        assertEquals(404, ex.status.value())
    }

    private companion object {
        const val CREATED_AT = "2026-11-01T08:00:00.000Z"
    }
}
