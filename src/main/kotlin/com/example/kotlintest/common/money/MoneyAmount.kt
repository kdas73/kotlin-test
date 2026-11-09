package com.example.kotlintest.common.money

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * The annotated `String` must be a request money string ([Money.REQUEST_PATTERN]) whose amount lies between [min] and
 * [max] (inclusive). [min] and [max] are money strings too, for example `"0.01"` and `"10000000.00"`.
 *
 * Combine with `@JsonDeserialize(using = StringOnlyDeserializer::class)` so that JSON numbers are rejected instead of
 * being coerced to strings. `null` is valid; combine with `@NotNull` to reject it.
 */
@MustBeDocumented
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [MoneyAmountValidator::class])
annotation class MoneyAmount(
    val min: String,
    val max: String,
    val message: String = "must be a money amount with 2 fraction digits from {min} to {max}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class MoneyAmountValidator : ConstraintValidator<MoneyAmount, String> {
    private var range: LongRange = LongRange.EMPTY

    override fun initialize(constraint: MoneyAmount) {
        val min = Money.parse(constraint.min)
        val max = Money.parse(constraint.max)
        require(max >= min) { "max (${constraint.max}) must not be less than min (${constraint.min})" }
        range = min..max
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val minorUnits = runCatching { Money.parse(value) }.getOrNull() ?: return false
        return minorUnits in range
    }
}
