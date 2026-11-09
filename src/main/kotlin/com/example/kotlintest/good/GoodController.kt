package com.example.kotlintest.good

import com.example.kotlintest.common.PageResponse
import com.example.kotlintest.common.idempotency.IdempotentExecutor
import com.example.kotlintest.common.money.Money
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/goods")
class GoodController(
    private val goodService: GoodService,
    private val idempotentExecutor: IdempotentExecutor,
) {

    /** Requires an `Idempotency-Key` header (see [IdempotentExecutor]). */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateGoodRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<GoodResponse> = idempotentExecutor.execute(httpRequest) {
        val good = GoodResponse.from(
            goodService.create(
                name = request.name,
                quantity = checkNotNull(request.quantity) { "quantity was validated as not null" },
                price = Money.parse(checkNotNull(request.price) { "price was validated as not null" }),
            ),
        )
        ResponseEntity.created(URI.create("/api/v1/goods/${good.id}")).body(good)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): GoodResponse = GoodResponse.from(goodService.get(id))

    /** Partial update. Idempotent by itself (absolute values), so no `Idempotency-Key` is required or recorded. */
    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateGoodRequest): GoodResponse =
        GoodResponse.from(
            goodService.update(id, request.name, request.quantity, request.price?.let(Money::parse)),
        )

    @GetMapping
    fun list(pageable: Pageable): PageResponse<GoodResponse> =
        PageResponse.from(goodService.list(pageable), GoodResponse::from)
}
