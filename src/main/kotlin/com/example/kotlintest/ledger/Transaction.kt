package com.example.kotlintest.ledger

import com.example.kotlintest.common.ApiException
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.http.HttpStatus
import java.time.Instant

enum class TransactionType {
    /** Money from the `FUNDING` account to a user's account (STORY-004). */
    DEPOSIT,

    /** Money from a user's account to the `REVENUE` account (STORY-005). */
    ORDER_PAYMENT,

    /** Money from the `REVENUE` account back to a user's account (STORY-006). */
    ORDER_REFUND,
}

/**
 * A balanced money movement (aggregate root). [amount] is the absolute amount moved in minor units (cents); the
 * [entries] are `-amount` on the account the money leaves and `+amount` on the account it enters, so they sum to `0`.
 *
 * Created only by [LedgerService.post]. Transactions are never changed after they are written.
 */
@Table("transactions")
data class Transaction(
    @Id val id: Long? = null,
    val type: TransactionType,
    val amount: Long,
    val createdAt: Instant,
    @MappedCollection(idColumn = "transaction_id")
    val entries: Set<TransactionEntry>,
)

class TransactionNotFoundException(id: Long) : ApiException(
    HttpStatus.NOT_FOUND,
    "Transaction not found",
    "Transaction $id does not exist.",
)
