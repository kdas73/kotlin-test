package com.example.kotlintest.common.json

import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Implementer's unit tests for [StringOnlyDeserializer] and [IntegerOnlyDeserializer] (STORY-003, not locked). */
class StrictDeserializersTest {

    data class Target(
        @param:JsonDeserialize(using = StringOnlyDeserializer::class)
        val text: String? = null,
        @param:JsonDeserialize(using = IntegerOnlyDeserializer::class)
        val number: Int? = null,
    )

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun read(json: String): Target = mapper.readValue(json, Target::class.java)

    @Test
    fun `accepts a JSON string and a JSON integer`() {
        assertEquals(Target(text = "15.00", number = 10), read("""{"text":"15.00","number":10}"""))
    }

    @Test
    fun `null and absent values stay null`() {
        assertEquals(Target(), read("""{"text":null,"number":null}"""))
        assertNull(read("{}").text)
    }

    @Test
    fun `rejects numbers, booleans and objects for a string`() {
        listOf("15", "15.00", "true", "{}", "[]").forEach { value ->
            assertFailsWith<DatabindException>("$value must be rejected") { read("""{"text":$value}""") }
        }
    }

    @Test
    fun `rejects floats, strings and booleans for an integer`() {
        listOf("10.5", "10.0", "1e3", "\"10\"", "true").forEach { value ->
            assertFailsWith<DatabindException>("$value must be rejected") { read("""{"number":$value}""") }
        }
    }

    @Test
    fun `rejects an integer that does not fit into an Int`() {
        assertFailsWith<Exception> { read("""{"number":2147483648}""") }
    }
}
