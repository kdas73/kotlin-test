package com.example.kotlintest.account

import com.example.kotlintest.common.json.StringOnlyDeserializer
import com.example.kotlintest.common.money.Money
import com.example.kotlintest.common.money.MoneyAmount
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotNull
import tools.jackson.databind.annotation.JsonDeserialize

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

/** Deposit request. `amount` must be a JSON string in the project money format; a JSON number is rejected. */
data class DepositRequest(
    @field:NotNull
    @field:MoneyAmount(min = MIN_AMOUNT, max = MAX_AMOUNT)
    val amount: String?,
) {
    companion object {
        const val MIN_AMOUNT = "0.01"
        const val MAX_AMOUNT = "10000000.00"

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        fun of(
            @JsonProperty("amount") @JsonDeserialize(using = StringOnlyDeserializer::class) amount: String?,
        ) = DepositRequest(amount = amount)
    }
}
