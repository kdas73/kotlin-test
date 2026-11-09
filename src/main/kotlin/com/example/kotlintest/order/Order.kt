package com.example.kotlintest.order

import com.example.kotlintest.common.ApiException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import org.springframework.http.HttpStatus
import java.time.Instant

enum class OrderStatus {
    /** Placed and paid. */
    PAID,

    /** Cancelled and refunded (STORY-006). */
    CANCELLED,
}

/**
 * An order for [quantity] items of one good (aggregate root). [unitPrice] is the good's price when the order was placed
 * and [totalAmount] is `quantity * unitPrice`; both are in integer minor units (cents).
 */
@Table("orders")
data class Order(
    @Id val id: Long? = null,
    val userId: Long,
    val goodId: Long,
    val quantity: Int,
    val unitPrice: Long,
    val totalAmount: Long,
    val status: OrderStatus,
    @Version val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class OrderNotFoundException(id: Long) : ApiException(HttpStatus.NOT_FOUND, "Order not found", "Order $id does not exist.")

class InsufficientStockException(goodId: Long, requested: Int, available: Int) : ApiException(
    HttpStatus.CONFLICT,
    "Insufficient stock",
    "Good $goodId has $available items in stock, $requested requested.",
)

class InsufficientFundsException(userId: Long) : ApiException(
    HttpStatus.CONFLICT,
    "Insufficient funds",
    "The balance of user $userId does not cover the order.",
)
