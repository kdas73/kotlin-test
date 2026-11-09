package com.example.kotlintest.order

import com.example.kotlintest.common.json.IntegerOnlyDeserializer
import com.example.kotlintest.common.money.Money
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant

/** Place-order request. `quantity` must be a JSON integer; `10.5` and `"10"` are rejected while reading. */
data class PlaceOrderRequest(
    @field:NotNull
    @field:Positive
    val userId: Long?,

    @field:NotNull
    @field:Positive
    val goodId: Long?,

    @field:NotNull
    @field:Min(MIN_QUANTITY)
    @field:Max(MAX_QUANTITY)
    val quantity: Int?,
) {
    companion object {
        const val MIN_QUANTITY = 1L
        const val MAX_QUANTITY = 10_000L

        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("userId") userId: Long?,
            @JsonProperty("goodId") goodId: Long?,
            @JsonProperty("quantity") @JsonDeserialize(using = IntegerOnlyDeserializer::class) quantity: Int?,
        ) = PlaceOrderRequest(userId = userId, goodId = goodId, quantity = quantity)
    }
}

/**
 * `unitPrice` and `totalAmount` are money strings (see [Money]). The transaction IDs are read from the ledger;
 * `refundTransactionId` is `null` until the order is refunded.
 */
data class OrderResponse(
    val id: Long,
    val userId: Long,
    val goodId: Long,
    val quantity: Int,
    val unitPrice: String,
    val totalAmount: String,
    val status: OrderStatus,
    val paymentTransactionId: Long?,
    val refundTransactionId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(order: Order, paymentTransactionId: Long?, refundTransactionId: Long?) = OrderResponse(
            id = checkNotNull(order.id) { "Order is not persisted" },
            userId = order.userId,
            goodId = order.goodId,
            quantity = order.quantity,
            unitPrice = Money.format(order.unitPrice),
            totalAmount = Money.format(order.totalAmount),
            status = order.status,
            paymentTransactionId = paymentTransactionId,
            refundTransactionId = refundTransactionId,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
        )
    }
}
