package com.example.kotlintest.order

import com.example.kotlintest.common.PageResponse
import com.example.kotlintest.common.idempotency.IdempotentExecutor
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService,
    private val idempotentExecutor: IdempotentExecutor,
) {

    /** Places and pays an order. Requires an `Idempotency-Key` header (see [IdempotentExecutor]). */
    @PostMapping
    fun place(
        @Valid @RequestBody request: PlaceOrderRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<OrderResponse> = idempotentExecutor.execute(httpRequest) {
        val order = toResponse(
            orderService.place(
                userId = checkNotNull(request.userId) { "userId was validated as not null" },
                goodId = checkNotNull(request.goodId) { "goodId was validated as not null" },
                quantity = checkNotNull(request.quantity) { "quantity was validated as not null" },
            ),
        )
        ResponseEntity.created(URI.create("/api/v1/orders/${order.id}")).body(order)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): OrderResponse = toResponse(orderService.get(id))

    @GetMapping
    fun list(@RequestParam(required = false) userId: Long?, pageable: Pageable): PageResponse<OrderResponse> =
        PageResponse.from(orderService.list(userId, pageable), ::toResponse)

    private fun toResponse(order: Order): OrderResponse {
        val id = checkNotNull(order.id) { "Order is not persisted" }
        return OrderResponse.from(order, orderService.paymentTransactionId(id), orderService.refundTransactionId(id))
    }
}
