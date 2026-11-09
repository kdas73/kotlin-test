package com.example.kotlintest.ledger

import com.example.kotlintest.account.Account
import com.example.kotlintest.account.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The double-entry ledger. This is the only code that changes `accounts.balance`: every balance change is posted as a
 * balanced [Transaction] in the same database transaction, so each account balance always equals the sum of its
 * entries.
 */
@Service
class LedgerService(
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {

    /**
     * Moves [amount] minor units from [from] to [to] and records it as a transaction of [type] with the entries
     * `-amount` on [from] and `+amount` on [to]. Joins the caller's transaction.
     *
     * Both accounts are written with their `@Version`, so the caller's reads of [from] and [to] (for example a balance
     * check) are still valid when the transaction commits.
     *
     * @param amount positive, in minor units (cents)
     * @param orderId the order the transaction pays or refunds, or `null` (deposits); must already exist
     * @throws org.springframework.dao.OptimisticLockingFailureException if one of the accounts was changed after the
     *   caller read it
     * @throws ArithmeticException if a balance would overflow a `Long`
     */
    @Transactional
    fun post(type: TransactionType, from: Account, to: Account, amount: Long, orderId: Long? = null): Transaction {
        require(amount > 0) { "Transaction amount must be positive, was $amount" }
        val fromId = checkNotNull(from.id) { "Account is not persisted" }
        val toId = checkNotNull(to.id) { "Account is not persisted" }
        require(fromId != toId) { "A transaction needs two different accounts, both were $fromId" }

        accounts.save(from.copy(balance = Math.subtractExact(from.balance, amount)))
        accounts.save(to.copy(balance = Math.addExact(to.balance, amount)))
        return transactions.save(
            Transaction(
                type = type,
                amount = amount,
                orderId = orderId,
                createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS),
                entries = setOf(
                    TransactionEntry(accountId = fromId, amount = -amount),
                    TransactionEntry(accountId = toId, amount = amount),
                ),
            ),
        )
    }

    /** The ID of the transaction of [type] that belongs to the order [orderId], or `null` if there is none. */
    fun findTransactionId(orderId: Long, type: TransactionType): Long? = transactions.findIdByOrderIdAndType(orderId, type.name)

    fun get(id: Long): Transaction = transactions.findById(id).orElseThrow { TransactionNotFoundException(id) }
}
