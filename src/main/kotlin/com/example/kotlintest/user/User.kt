package com.example.kotlintest.user

import com.example.kotlintest.common.ApiException
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.http.HttpStatus
import java.time.Instant

/** A shop customer. `email` is stored trimmed and in lower case. */
@Table("users")
data class User(
    @Id val id: Long? = null,
    val name: String,
    val email: String,
    val createdAt: Instant,
)

class UserNotFoundException(id: Long) : ApiException(HttpStatus.NOT_FOUND, "User not found", "User $id does not exist.")

class EmailAlreadyInUseException : ApiException(
    HttpStatus.CONFLICT,
    "Email already in use",
    "Another user already has this email.",
)
