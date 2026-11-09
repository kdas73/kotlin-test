package com.example.kotlintest.account

import org.springframework.data.repository.ListCrudRepository

interface AccountRepository : ListCrudRepository<Account, Long> {

    fun findByUserId(userId: Long): Account?

    /** The system account of [kind] (`FUNDING` or `REVENUE`); there is exactly one of each. */
    fun findByKindAndUserIdIsNull(kind: AccountKind): Account?
}
