package com.example.kotlintest.order

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.PagingAndSortingRepository

interface OrderRepository : ListCrudRepository<Order, Long>, PagingAndSortingRepository<Order, Long> {

    fun findByUserId(userId: Long, pageable: Pageable): Page<Order>
}
