package com.example.kotlintest.order

import com.example.kotlintest.account.AccountKind
import com.example.kotlintest.account.AccountService
import com.example.kotlintest.common.withValidatedSort
import com.example.kotlintest.good.GoodService
import com.example.kotlintest.ledger.LedgerService
import com.example.kotlintest.ledger.TransactionType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class OrderService(
    private val orders: OrderRepository,
    private val goodService: GoodService,
    private val accountService: AccountService,
    private val ledgerService: LedgerService,
    private val clock: Clock,
) {

    /**
     * Places and pays an order in the caller's transaction (the idempotent executor's): checks the stock and the
     * balance, takes the items out of the stock, stores the order as `PAID` and posts an `ORDER_PAYMENT` transaction
     * from the user's account to the `REVENUE` account. All of it happens or none of it.
     *
     * The good and both accounts are read and then written with their `@Version`, so a concurrent change after the
     * reads makes the write fail instead of overselling or overdrawing.
     *
     * @param quantity validated to be in `1..10000`
     * @throws com.example.kotlintest.user.UserNotFoundException if the user does not exist
     * @throws com.example.kotlintest.good.GoodNotFoundException if the good does not exist
     * @throws InsufficientStockException if the stock is lower than [quantity]
     * @throws InsufficientFundsException if the user's balance is lower than the total amount
     * @throws org.springframework.dao.OptimisticLockingFailureException if the good or an account was changed concurrently
     */
    @Transactional
    fun place(userId: Long, goodId: Long, quantity: Int): Order {
        val userAccount = accountService.getByUserId(userId)
        val good = goodService.get(goodId)
        if (good.quantity < quantity) throw InsufficientStockException(goodId, quantity, good.quantity)
        val totalAmount = Math.multiplyExact(good.price, quantity.toLong())
        if (userAccount.balance < totalAmount) throw InsufficientFundsException(userId)

        val now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        goodService.takeStock(good, quantity)
        val order = orders.save(
            Order(
                userId = userId,
                goodId = goodId,
                quantity = quantity,
                unitPrice = good.price,
                totalAmount = totalAmount,
                status = OrderStatus.PAID,
                createdAt = now,
                updatedAt = now,
            ),
        )
        ledgerService.post(
            TransactionType.ORDER_PAYMENT,
            from = userAccount,
            to = accountService.getSystemAccount(AccountKind.REVENUE),
            amount = totalAmount,
            orderId = checkNotNull(order.id) { "Order was not assigned an id" },
        )
        return order
    }

    fun get(id: Long): Order = orders.findById(id).orElseThrow { OrderNotFoundException(id) }

    /** Lists the orders of user [userId], or all orders if it is `null`. An unknown user has no orders. */
    fun list(userId: Long?, pageable: Pageable): Page<Order> {
        val validated = pageable.withValidatedSort(SORT_PROPERTIES)
        return if (userId == null) orders.findAll(validated) else orders.findByUserId(userId, validated)
    }

    /** The ID of the `ORDER_PAYMENT` transaction of the order, or `null` if there is none. */
    fun paymentTransactionId(orderId: Long): Long? =
        ledgerService.findTransactionId(orderId, TransactionType.ORDER_PAYMENT)

    /** The ID of the `ORDER_REFUND` transaction of the order, or `null` if there is none. */
    fun refundTransactionId(orderId: Long): Long? =
        ledgerService.findTransactionId(orderId, TransactionType.ORDER_REFUND)

    private companion object {
        val SORT_PROPERTIES = setOf("id", "createdAt", "totalAmount")
    }
}
