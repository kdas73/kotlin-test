package com.example.kotlintest.good

import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.PagingAndSortingRepository

interface GoodRepository : ListCrudRepository<Good, Long>, PagingAndSortingRepository<Good, Long>
