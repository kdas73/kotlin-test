package com.example.kotlintest.account

import com.example.kotlintest.user.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AccountService(private val accounts: AccountRepository) {

    /** Opens the single account of a new user with a zero balance. Joins the caller's transaction. */
    @Transactional
    fun openUserAccount(userId: Long, createdAt: Instant): Account =
        accounts.save(Account(userId = userId, kind = AccountKind.USER, balance = 0, createdAt = createdAt))

    /** Every user has exactly one account, so a missing account means the user does not exist. */
    fun getByUserId(userId: Long): Account =
        accounts.findByUserId(userId) ?: throw UserNotFoundException(userId)

    /** The system account of [kind]. The ledger migration creates exactly one `FUNDING` and one `REVENUE` account. */
    fun getSystemAccount(kind: AccountKind): Account {
        require(kind != AccountKind.USER) { "USER is not a system account kind" }
        return accounts.findByKindAndUserIdIsNull(kind) ?: error("System account $kind is missing")
    }
}
