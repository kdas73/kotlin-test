package com.example.kotlintest.account

import com.example.kotlintest.common.idempotency.IdempotentExecutor
import com.example.kotlintest.common.money.Money
import com.example.kotlintest.ledger.TransactionResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
class DepositController(
    private val depositService: DepositService,
    private val idempotentExecutor: IdempotentExecutor,
) {

    /** Requires an `Idempotency-Key` header (see [IdempotentExecutor]); a repeated request credits the account once. */
    @PostMapping("/api/v1/users/{userId}/account/deposits")
    fun deposit(
        @PathVariable userId: Long,
        @Valid @RequestBody request: DepositRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<TransactionResponse> = idempotentExecutor.execute(httpRequest) {
        val amount = Money.parse(checkNotNull(request.amount) { "amount was validated as not null" })
        val transaction = TransactionResponse.from(depositService.deposit(userId, amount))
        ResponseEntity.created(URI.create("/api/v1/transactions/${transaction.id}")).body(transaction)
    }
}
