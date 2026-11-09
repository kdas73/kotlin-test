package com.example.kotlintest.user

import com.example.kotlintest.account.AccountService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Implementer's unit tests for [UserService.update] (STORY-002, not locked). */
class UserServiceTest {

    private val users = mock(UserRepository::class.java)
    private val service = UserService(users, mock(AccountService::class.java), Clock.systemUTC())

    private val user = User(id = 1, name = "Alice", email = "alice@example.com", createdAt = Instant.parse(CREATED_AT))

    @Test
    fun `a UNIQUE violation on save is mapped to EmailAlreadyInUseException`() {
        // Another request took the email between the existence check and the UPDATE.
        `when`(users.findById(1)).thenReturn(Optional.of(user))
        `when`(users.existsByEmail("taken@example.com")).thenReturn(false)
        val updated = user.copy(email = "taken@example.com")
        val sqlError = SQLException(
            "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: users.email)",
            null,
            SQLITE_CONSTRAINT,
        )
        doThrow(DataIntegrityViolationException("UPDATE failed", sqlError)).`when`(users).save(updated)

        assertFailsWith<EmailAlreadyInUseException> { service.update(1, null, "Taken@Example.com") }
    }

    @Test
    fun `other data access errors on save are not mapped`() {
        `when`(users.findById(1)).thenReturn(Optional.of(user))
        val updated = user.copy(name = "Alice Smith")
        doThrow(DataIntegrityViolationException("CHECK constraint failed")).`when`(users).save(updated)

        assertFailsWith<DataIntegrityViolationException> { service.update(1, "Alice Smith", null) }
    }

    @Test
    fun `setting the current values does not write`() {
        `when`(users.findById(1)).thenReturn(Optional.of(user))

        val result = service.update(1, "Alice", "ALICE@example.com")

        assertSame(user, result)
        verify(users, never()).existsByEmail("alice@example.com")
        verify(users, never()).save(any<User>())
    }

    @Test
    fun `null arguments keep the current values`() {
        `when`(users.findById(1)).thenReturn(Optional.of(user))
        val updated = user.copy(name = "Alice Smith")
        `when`(users.save(updated)).thenReturn(updated)

        assertEquals(updated, service.update(1, "Alice Smith", null))
    }

    @Test
    fun `an unknown user throws UserNotFoundException`() {
        `when`(users.findById(2)).thenReturn(Optional.empty())

        assertFailsWith<UserNotFoundException> { service.update(2, "Bob", null) }
    }

    private companion object {
        const val CREATED_AT = "2026-11-08T12:00:00.123Z"
        const val SQLITE_CONSTRAINT = 19
    }
}
