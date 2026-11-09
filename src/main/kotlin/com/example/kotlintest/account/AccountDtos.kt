package com.example.kotlintest.account

import com.example.kotlintest.common.money.Money

/** `balance` is a money string (see [Money]). */
data class AccountResponse(
    val id: Long,
    val userId: Long,
    val balance: String,
) {
    companion object {
        fun from(account: Account) = AccountResponse(
            id = checkNotNull(account.id) { "Account is not persisted" },
            userId = checkNotNull(account.userId) { "Account ${account.id} has no user" },
            balance = Money.format(account.balance),
        )
    }
}
