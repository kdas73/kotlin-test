package com.example.kotlintest.account

import com.example.kotlintest.ledger.LedgerService
import com.example.kotlintest.ledger.Transaction
import com.example.kotlintest.ledger.TransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DepositService(
    private val accountService: AccountService,
    private val ledgerService: LedgerService,
) {

    /**
     * Deposits [amount] into the account of user [userId]: a `DEPOSIT` transaction from the `FUNDING` account to the
     * user's account. Joins the caller's transaction (the idempotent executor's).
     *
     * @param amount positive, in minor units (cents), already validated
     * @throws com.example.kotlintest.user.UserNotFoundException if the user does not exist
     * @throws org.springframework.dao.OptimisticLockingFailureException if one of the accounts was changed concurrently
     */
    @Transactional
    fun deposit(userId: Long, amount: Long): Transaction {
        val account = accountService.getByUserId(userId)
        val funding = accountService.getSystemAccount(AccountKind.FUNDING)
        return ledgerService.post(TransactionType.DEPOSIT, from = funding, to = account, amount = amount)
    }
}
