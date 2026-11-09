package com.example.kotlintest.ledger

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.query.Param

interface TransactionRepository : ListCrudRepository<Transaction, Long> {

    /** The ID of the transaction of the given [type] name that belongs to [orderId]; at most one (unique index). */
    @Query("SELECT id FROM transactions WHERE order_id = :orderId AND type = :type")
    fun findIdByOrderIdAndType(@Param("orderId") orderId: Long, @Param("type") type: String): Long?
}
