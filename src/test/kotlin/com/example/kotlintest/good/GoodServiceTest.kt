package com.example.kotlintest.good

import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Implementer's unit tests for [GoodService] (STORY-003, not locked). */
class GoodServiceTest {

    private val goods = mock(GoodRepository::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00.123456Z"), ZoneOffset.UTC)
    private val service = GoodService(goods, clock)

    private val good = Good(
        id = 1,
        name = "Mug",
        quantity = 10,
        price = 1500,
        version = 3,
        createdAt = Instant.parse(CREATED_AT),
        updatedAt = Instant.parse(CREATED_AT),
    )

    @Test
    fun `create sets createdAt and updatedAt to the current time truncated to milliseconds`() {
        val expected = Good(
            name = "Mug",
            quantity = 10,
            price = 1500,
            createdAt = Instant.parse(NOW),
            updatedAt = Instant.parse(NOW),
        )
        `when`(goods.save(expected)).thenReturn(expected.copy(id = 7))

        assertEquals(7L, service.create("Mug", 10, 1500).id)
    }

    @Test
    fun `setting the current values does not write`() {
        `when`(goods.findById(1)).thenReturn(Optional.of(good))

        val result = service.update(1, "Mug", 10, 1500)

        assertSame(good, result)
        verify(goods, never()).save(any<Good>())
    }

    @Test
    fun `a changed value is saved with a new updatedAt and the loaded version`() {
        `when`(goods.findById(1)).thenReturn(Optional.of(good))
        val expected = good.copy(quantity = 25, updatedAt = Instant.parse(NOW))
        `when`(goods.save(expected)).thenReturn(expected.copy(version = 4))

        val result = service.update(1, null, 25, null)

        assertEquals(25, result.quantity)
        assertEquals(Instant.parse(NOW), result.updatedAt)
        assertEquals(good.createdAt, result.createdAt)
    }

    @Test
    fun `null arguments keep the current values`() {
        `when`(goods.findById(1)).thenReturn(Optional.of(good))
        val expected = good.copy(price = 2500, updatedAt = Instant.parse(NOW))
        `when`(goods.save(expected)).thenReturn(expected)

        val result = service.update(1, null, null, 2500)

        assertEquals("Mug", result.name)
        assertEquals(10, result.quantity)
        assertEquals(2500L, result.price)
    }

    @Test
    fun `an unknown good throws GoodNotFoundException`() {
        `when`(goods.findById(2)).thenReturn(Optional.empty())

        assertFailsWith<GoodNotFoundException> { service.update(2, null, 1, null) }
        assertFailsWith<GoodNotFoundException> { service.get(2) }
    }

    private companion object {
        const val CREATED_AT = "2026-11-08T12:00:00.123Z"
        const val NOW = "2026-11-09T10:00:00.123Z"
    }
}
