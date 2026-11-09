package com.example.kotlintest.ledger

import org.springframework.data.repository.ListCrudRepository

interface TransactionRepository : ListCrudRepository<Transaction, Long>
