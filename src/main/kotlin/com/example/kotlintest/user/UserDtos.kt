package com.example.kotlintest.user

import com.example.kotlintest.common.validation.CodePointLength
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * Create-user request. Values are trimmed while the JSON is read, so validation applies to the trimmed values.
 * A missing field becomes `""` and fails `@NotBlank`. Lengths are counted in Unicode code points.
 */
data class CreateUserRequest(
    @field:NotBlank
    @field:CodePointLength(min = 1, max = 100)
    val name: String,

    @field:NotBlank
    @field:CodePointLength(max = 254)
    @field:Pattern(regexp = EMAIL_PATTERN)
    val email: String,
) {
    companion object {
        const val EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"

        @JvmStatic
        @JsonCreator
        fun of(@JsonProperty("name") name: String?, @JsonProperty("email") email: String?) =
            CreateUserRequest(name = name?.trim().orEmpty(), email = email?.trim().orEmpty())
    }
}

/**
 * Partial update of a user. An absent or `null` field stays unchanged; at least one field must be given.
 * Non-null values are trimmed while the JSON is read, so a blank value becomes `""` and fails the minimum length.
 * Validation and normalization are the same as in [CreateUserRequest]. Lengths are counted in Unicode code points.
 */
data class UpdateUserRequest(
    @field:CodePointLength(min = 1, max = 100)
    val name: String?,

    @field:CodePointLength(min = 1, max = 254)
    @field:Pattern(regexp = CreateUserRequest.EMAIL_PATTERN)
    val email: String?,
) {
    @get:AssertTrue(message = "at least one of name or email must be given")
    val isChangeRequested: Boolean
        get() = name != null || email != null

    companion object {
        @JvmStatic
        @JsonCreator
        fun of(@JsonProperty("name") name: String?, @JsonProperty("email") email: String?) =
            UpdateUserRequest(name = name?.trim(), email = email?.trim())
    }
}

data class UserResponse(
    val id: Long,
    val name: String,
    val email: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = checkNotNull(user.id) { "User is not persisted" },
            name = user.name,
            email = user.email,
            createdAt = user.createdAt,
        )
    }
}
