package com.example.kotlintest.ledger

import com.example.kotlintest.common.money.Money
import java.time.Instant

/**
 * A transaction with its entries. `amount` and `entries[].amount` are money strings (see [Money]); entry amounts are
 * signed. `entries` are ordered by amount ascending, so the negative entry comes first.
 */
data class TransactionResponse(
    val id: Long,
    val type: TransactionType,
    val amount: String,
    val createdAt: Instant,
    val entries: List<TransactionEntryResponse>,
) {
    companion object {
        fun from(transaction: Transaction) = TransactionResponse(
            id = checkNotNull(transaction.id) { "Transaction is not persisted" },
            type = transaction.type,
            amount = Money.format(transaction.amount),
            createdAt = transaction.createdAt,
            entries = transaction.entries
                .sortedWith(compareBy(TransactionEntry::amount, TransactionEntry::accountId))
                .map { TransactionEntryResponse(accountId = it.accountId, amount = Money.format(it.amount)) },
        )
    }
}

data class TransactionEntryResponse(
    val accountId: Long,
    val amount: String,
)
