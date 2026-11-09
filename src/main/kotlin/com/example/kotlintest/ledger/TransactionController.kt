package com.example.kotlintest.ledger

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(private val ledgerService: LedgerService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): TransactionResponse = TransactionResponse.from(ledgerService.get(id))
}
