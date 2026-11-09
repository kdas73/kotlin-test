package com.example.kotlintest.order

import com.example.kotlintest.account.Account
import com.example.kotlintest.account.AccountKind
import com.example.kotlintest.account.AccountService
import com.example.kotlintest.good.Good
import com.example.kotlintest.good.GoodNotFoundException
import com.example.kotlintest.good.GoodService
import com.example.kotlintest.ledger.LedgerService
import com.example.kotlintest.ledger.TransactionType
import com.example.kotlintest.user.UserNotFoundException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Implementer's unit tests for [OrderService] (STORY-005, not locked). */
class OrderServiceTest {

    private val orders = mock(OrderRepository::class.java)
    private val goodService = mock(GoodService::class.java)
    private val accountService = mock(AccountService::class.java)
    private val ledgerService = mock(LedgerService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00.123456Z"), ZoneOffset.UTC)
    private val service = OrderService(orders, goodService, accountService, ledgerService, clock)

    private val now = Instant.parse("2026-11-09T10:00:00.123Z")
    private val userAccount = Account(
        id = 7,
        userId = 3,
        kind = AccountKind.USER,
        balance = 10_000,
        version = 2,
        createdAt = now,
    )
    private val revenue = Account(id = 2, userId = null, kind = AccountKind.REVENUE, balance = 0, version = 1, createdAt = now)
    private val good = Good(id = 5, name = "Mug", quantity = 10, price = 1_500, version = 4, createdAt = now, updatedAt = now)

    @Test
    fun `place stores a paid order at the current price and pays it from the user account to REVENUE`() {
        `when`(accountService.getByUserId(3)).thenReturn(userAccount)
        `when`(goodService.get(5)).thenReturn(good)
        `when`(accountService.getSystemAccount(AccountKind.REVENUE)).thenReturn(revenue)
        val expected = Order(
            userId = 3,
            goodId = 5,
            quantity = 3,
            unitPrice = 1_500,
            totalAmount = 4_500,
            status = OrderStatus.PAID,
            createdAt = now,
            updatedAt = now,
        )
        `when`(orders.save(expected)).thenReturn(expected.copy(id = 11))

        val order = service.place(userId = 3, goodId = 5, quantity = 3)

        assertEquals(11L, order.id)
        verify(goodService).takeStock(good, 3)
        verify(ledgerService).post(TransactionType.ORDER_PAYMENT, userAccount, revenue, 4_500, 11)
    }

    @Test
    fun `place accepts an order that uses the whole stock and the whole balance`() {
        val exactGood = good.copy(quantity = 4, price = 2_500)
        `when`(accountService.getByUserId(3)).thenReturn(userAccount)
        `when`(goodService.get(5)).thenReturn(exactGood)
        `when`(accountService.getSystemAccount(AccountKind.REVENUE)).thenReturn(revenue)
        `when`(orders.save(org.mockito.ArgumentMatchers.any(Order::class.java)))
            .thenAnswer { it.getArgument<Order>(0).copy(id = 12) }

        val order = service.place(userId = 3, goodId = 5, quantity = 4)

        assertEquals(10_000L, order.totalAmount)
        verify(goodService).takeStock(exactGood, 4)
    }

    @Test
    fun `place rejects an order larger than the stock before checking the balance`() {
        `when`(accountService.getByUserId(3)).thenReturn(userAccount.copy(balance = 0))
        `when`(goodService.get(5)).thenReturn(good)

        val e = assertFailsWith<InsufficientStockException> { service.place(userId = 3, goodId = 5, quantity = 11) }

        assertEquals("Insufficient stock", e.title)
        verifyNoInteractions(orders, ledgerService)
    }

    @Test
    fun `place rejects an order the balance does not cover without writing`() {
        `when`(accountService.getByUserId(3)).thenReturn(userAccount.copy(balance = 4_499))
        `when`(goodService.get(5)).thenReturn(good)

        val e = assertFailsWith<InsufficientFundsException> { service.place(userId = 3, goodId = 5, quantity = 3) }

        assertEquals("Insufficient funds", e.title)
        verifyNoInteractions(orders, ledgerService)
        verify(goodService).get(5)
        verifyNoMoreInteractions(goodService)
    }

    @Test
    fun `place reports an unknown user before an unknown good`() {
        `when`(accountService.getByUserId(3)).thenThrow(UserNotFoundException(3))
        `when`(goodService.get(5)).thenThrow(GoodNotFoundException(5))

        assertFailsWith<UserNotFoundException> { service.place(userId = 3, goodId = 5, quantity = 1) }
        verifyNoInteractions(orders, ledgerService)
    }

    @Test
    fun `place reports an unknown good`() {
        `when`(accountService.getByUserId(3)).thenReturn(userAccount)
        `when`(goodService.get(5)).thenThrow(GoodNotFoundException(5))

        assertFailsWith<GoodNotFoundException> { service.place(userId = 3, goodId = 5, quantity = 1) }
        verifyNoInteractions(orders, ledgerService)
    }
}
