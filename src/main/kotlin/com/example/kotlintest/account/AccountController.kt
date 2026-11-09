package com.example.kotlintest.account

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(private val accountService: AccountService) {

    @GetMapping("/api/v1/users/{userId}/account")
    fun getUserAccount(@PathVariable userId: Long): AccountResponse =
        AccountResponse.from(accountService.getByUserId(userId))
}
