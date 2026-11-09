package com.example.kotlintest.good

import com.example.kotlintest.common.json.IntegerOnlyDeserializer
import com.example.kotlintest.common.json.StringOnlyDeserializer
import com.example.kotlintest.common.money.Money
import com.example.kotlintest.common.money.MoneyAmount
import com.example.kotlintest.common.validation.CodePointLength
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant

/**
 * Create-good request. `name` is trimmed while the JSON is read, so validation applies to the trimmed value; a missing
 * name becomes `""` and fails `@NotBlank`. `quantity` must be a JSON integer and `price` a JSON string in the project
 * money format; other JSON types are rejected while reading. Lengths are counted in Unicode code points.
 */
data class CreateGoodRequest(
    @field:NotBlank
    @field:CodePointLength(min = 1, max = MAX_NAME_LENGTH)
    val name: String,

    @field:NotNull
    @field:Min(MIN_QUANTITY)
    @field:Max(MAX_QUANTITY)
    val quantity: Int?,

    @field:NotNull
    @field:MoneyAmount(min = MIN_PRICE, max = MAX_PRICE)
    val price: String?,
) {
    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MIN_QUANTITY = 0L
        const val MAX_QUANTITY = 1_000_000L
        const val MIN_PRICE = "0.01"
        const val MAX_PRICE = "10000000.00"

        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("name") name: String?,
            @JsonProperty("quantity") @JsonDeserialize(using = IntegerOnlyDeserializer::class) quantity: Int?,
            @JsonProperty("price") @JsonDeserialize(using = StringOnlyDeserializer::class) price: String?,
        ) = CreateGoodRequest(name = name?.trim().orEmpty(), quantity = quantity, price = price)
    }
}

/**
 * Partial update of a good with absolute values. An absent or `null` field stays unchanged; at least one field must be
 * given. Non-null values are validated and normalized as in [CreateGoodRequest]; a blank name becomes `""` and fails
 * the minimum length.
 */
data class UpdateGoodRequest(
    @field:CodePointLength(min = 1, max = CreateGoodRequest.MAX_NAME_LENGTH)
    val name: String?,

    @field:Min(CreateGoodRequest.MIN_QUANTITY)
    @field:Max(CreateGoodRequest.MAX_QUANTITY)
    val quantity: Int?,

    @field:MoneyAmount(min = CreateGoodRequest.MIN_PRICE, max = CreateGoodRequest.MAX_PRICE)
    val price: String?,
) {
    @get:AssertTrue(message = "at least one of name, quantity or price must be given")
    val isChangeRequested: Boolean
        get() = name != null || quantity != null || price != null

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("name") name: String?,
            @JsonProperty("quantity") @JsonDeserialize(using = IntegerOnlyDeserializer::class) quantity: Int?,
            @JsonProperty("price") @JsonDeserialize(using = StringOnlyDeserializer::class) price: String?,
        ) = UpdateGoodRequest(name = name?.trim(), quantity = quantity, price = price)
    }
}

/** `price` is a money string (see [Money]). */
data class GoodResponse(
    val id: Long,
    val name: String,
    val quantity: Int,
    val price: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(good: Good) = GoodResponse(
            id = checkNotNull(good.id) { "Good is not persisted" },
            name = good.name,
            quantity = good.quantity,
            price = Money.format(good.price),
            createdAt = good.createdAt,
            updatedAt = good.updatedAt,
        )
    }
}
