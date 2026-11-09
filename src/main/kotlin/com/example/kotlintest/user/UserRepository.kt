package com.example.kotlintest.user

import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.PagingAndSortingRepository

interface UserRepository : ListCrudRepository<User, Long>, PagingAndSortingRepository<User, Long> {

    fun existsByEmail(email: String): Boolean
}
