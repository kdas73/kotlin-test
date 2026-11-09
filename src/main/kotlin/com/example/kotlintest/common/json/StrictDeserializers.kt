package com.example.kotlintest.common.json

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

/**
 * Accepts only a JSON string. By default Jackson coerces scalars, so the number `15` would become the string `"15"`;
 * with this deserializer it is rejected (the request becomes a `400`). Use it on fields where the JSON type matters,
 * for example money (`@JsonDeserialize(using = StringOnlyDeserializer::class)`).
 *
 * JSON `null` never reaches this deserializer and stays `null`.
 */
class StringOnlyDeserializer : ValueDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String =
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            p.getString()
        } else {
            ctxt.handleUnexpectedToken(String::class.java, p) as String
        }
}

/**
 * Accepts only a JSON integer number that fits into an `Int`. By default Jackson coerces `10.5` to `10` and `"10"` to
 * `10`; with this deserializer both are rejected (the request becomes a `400`), and so is an `Int` overflow.
 *
 * JSON `null` never reaches this deserializer and stays `null`.
 */
class IntegerOnlyDeserializer : ValueDeserializer<Int>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Int =
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            p.getIntValue()
        } else {
            ctxt.handleUnexpectedToken(Int::class.javaObjectType, p) as Int
        }
}
