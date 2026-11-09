package com.example.kotlintest.user

import com.example.kotlintest.account.AccountService
import com.example.kotlintest.common.isUniqueViolation
import com.example.kotlintest.common.withValidatedSort
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

@Service
class UserService(
    private val users: UserRepository,
    private val accountService: AccountService,
    private val clock: Clock,
) {

    /**
     * Registers a user and opens their account in one transaction.
     *
     * @param name already trimmed and validated
     * @param email already trimmed and validated; stored in lower case
     * @throws EmailAlreadyInUseException if another user has the same email in any letter case
     */
    @Transactional
    fun register(name: String, email: String): User {
        val normalizedEmail = email.lowercase(Locale.ROOT)
        if (users.existsByEmail(normalizedEmail)) throw EmailAlreadyInUseException()
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        val user = try {
            users.save(User(name = name, email = normalizedEmail, createdAt = now))
        } catch (e: DataAccessException) {
            if (e.isUniqueViolation("users.email")) throw EmailAlreadyInUseException()
            throw e
        }
        accountService.openUserAccount(checkNotNull(user.id) { "User was not assigned an id" }, now)
        return user
    }

    /**
     * Updates the given fields of a user; a `null` argument leaves the field unchanged. Setting the values the user
     * already has is a no-op, so repeating an update gives the same result.
     *
     * @param name already trimmed and validated, or `null`
     * @param email already trimmed and validated, or `null`; stored in lower case
     * @throws UserNotFoundException if the user does not exist
     * @throws EmailAlreadyInUseException if another user has the same email in any letter case
     */
    @Transactional
    fun update(id: Long, name: String?, email: String?): User {
        val user = get(id)
        val normalizedEmail = email?.lowercase(Locale.ROOT)
        if (normalizedEmail != null && normalizedEmail != user.email && users.existsByEmail(normalizedEmail)) {
            throw EmailAlreadyInUseException()
        }
        val updated = user.copy(name = name ?: user.name, email = normalizedEmail ?: user.email)
        if (updated == user) return user
        return try {
            users.save(updated)
        } catch (e: DataAccessException) {
            if (e.isUniqueViolation("users.email")) throw EmailAlreadyInUseException()
            throw e
        }
    }

    fun get(id: Long): User = users.findById(id).orElseThrow { UserNotFoundException(id) }

    fun list(pageable: Pageable): Page<User> = users.findAll(pageable.withValidatedSort(SORT_PROPERTIES))

    private companion object {
        val SORT_PROPERTIES = setOf("id", "name", "email", "createdAt")
    }
}
