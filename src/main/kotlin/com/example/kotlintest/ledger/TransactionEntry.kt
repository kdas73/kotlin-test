package com.example.kotlintest.ledger

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * One side of a [Transaction]: [amount] in minor units (cents) on the account [accountId]. A negative amount means
 * money leaves the account, a positive amount means money enters it.
 */
@Table("transaction_entries")
data class TransactionEntry(
    @Id val id: Long? = null,
    val accountId: Long,
    val amount: Long,
)
