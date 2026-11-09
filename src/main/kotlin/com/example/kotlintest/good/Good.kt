package com.example.kotlintest.good

import com.example.kotlintest.common.ApiException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * A good sold by the shop. [price] is in integer minor units (cents).
 *
 * [version] enables optimistic locking: stock is also changed by orders (STORY-005), concurrently with updates.
 */
@Table("goods")
data class Good(
    @Id val id: Long? = null,
    val name: String,
    val quantity: Int,
    val price: Long,
    @Version val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class GoodNotFoundException(id: Long) : ApiException(HttpStatus.NOT_FOUND, "Good not found", "Good $id does not exist.")
