package com.example.kotlintest.common.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * The annotated `String` must be between [min] and [max] (inclusive) **Unicode code points** long.
 *
 * Unlike `@Size`, which counts UTF-16 code units, a character outside the Basic Multilingual Plane (for example an
 * emoji) counts as one. Every text length limit in the API uses this constraint instead of `@Size`.
 *
 * `null` is valid; combine with `@NotNull` or `@NotBlank` to reject it.
 */
@MustBeDocumented
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [CodePointLengthValidator::class])
annotation class CodePointLength(
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val message: String = "length must be between {min} and {max} characters",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class CodePointLengthValidator : ConstraintValidator<CodePointLength, String> {
    private var min = 0
    private var max = Int.MAX_VALUE

    override fun initialize(constraint: CodePointLength) {
        require(constraint.min >= 0) { "min must not be negative: ${constraint.min}" }
        require(constraint.max >= constraint.min) { "max (${constraint.max}) must not be less than min (${constraint.min})" }
        min = constraint.min
        max = constraint.max
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val length = value.codePointCount(0, value.length)
        return length in min..max
    }
}
