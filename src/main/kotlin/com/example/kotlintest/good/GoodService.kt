package com.example.kotlintest.good

import com.example.kotlintest.common.withValidatedSort
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class GoodService(
    private val goods: GoodRepository,
    private val clock: Clock,
) {

    /**
     * Creates a good.
     *
     * @param name already trimmed and validated
     * @param price in minor units (cents), already validated
     */
    @Transactional
    fun create(name: String, quantity: Int, price: Long): Good {
        val now = now()
        return goods.save(Good(name = name, quantity = quantity, price = price, createdAt = now, updatedAt = now))
    }

    fun get(id: Long): Good = goods.findById(id).orElseThrow { GoodNotFoundException(id) }

    fun list(pageable: Pageable): Page<Good> = goods.findAll(pageable.withValidatedSort(SORT_PROPERTIES))

    /**
     * Sets the given fields of a good to absolute values; a `null` argument leaves the field unchanged. Setting the
     * values the good already has is a no-op: nothing is written, so `updatedAt` and the version stay the same and
     * repeating an update gives the same result.
     *
     * @param name already trimmed and validated, or `null`
     * @param price in minor units (cents), already validated, or `null`
     * @throws GoodNotFoundException if the good does not exist
     * @throws org.springframework.dao.OptimisticLockingFailureException if the good was changed concurrently
     */
    @Transactional
    fun update(id: Long, name: String?, quantity: Int?, price: Long?): Good {
        val good = get(id)
        val changed = good.copy(
            name = name ?: good.name,
            quantity = quantity ?: good.quantity,
            price = price ?: good.price,
        )
        if (changed == good) return good
        return goods.save(changed.copy(updatedAt = now()))
    }

    /**
     * Takes [quantity] items out of the stock of [good], which the caller loaded in the current transaction. The write
     * uses the good's `@Version`, so a concurrent change of the good after the caller read it makes this fail. Joins the
     * caller's transaction.
     *
     * @param quantity positive, at most `good.quantity`
     * @throws org.springframework.dao.OptimisticLockingFailureException if the good was changed after the caller read it
     */
    @Transactional
    fun takeStock(good: Good, quantity: Int): Good {
        require(quantity in 1..good.quantity) { "Cannot take $quantity of ${good.quantity} items in stock" }
        return goods.save(good.copy(quantity = good.quantity - quantity, updatedAt = now()))
    }

    private fun now(): Instant = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        val SORT_PROPERTIES = setOf("id", "name", "quantity", "price", "createdAt")
    }
}
