package com.example.kotlintest.account

import org.springframework.data.repository.ListCrudRepository

interface AccountRepository : ListCrudRepository<Account, Long> {

    fun findByUserId(userId: Long): Account?
}
