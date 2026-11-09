package com.example.kotlintest.user

import com.example.kotlintest.common.isUniqueViolation
import com.example.kotlintest.config.SqliteDialectConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** First temporal column in the project: `Instant` <-> ISO-8601 UTC `TEXT` round trip (AGENTS.md). */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SqliteDialectConfig::class)
@ActiveProfiles("test")
class UserPersistenceTest(
    @Autowired private val users: UserRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `stores Instant as ISO-8601 UTC text with millisecond precision and reads it back`() {
        val createdAt = Instant.parse("2026-11-08T12:00:00.123Z")
        val saved = users.save(User(name = "Alice", email = uniqueEmail(), createdAt = createdAt))

        val row = jdbc.queryForMap(
            "SELECT created_at, typeof(created_at) AS type FROM users WHERE id = ?",
            saved.id,
        )
        assertEquals("2026-11-08T12:00:00.123Z", row["created_at"])
        assertEquals("text", row["type"])
        assertEquals(createdAt, users.findById(checkNotNull(saved.id)).orElseThrow().createdAt)
    }

    @Test
    fun `always writes three fraction digits so the text sorts like the instant`() {
        val saved = users.save(
            User(name = "Bob", email = uniqueEmail(), createdAt = Instant.parse("2026-11-08T12:00:00Z")),
        )

        val text = jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?", String::class.java, saved.id)
        assertEquals("2026-11-08T12:00:00.000Z", text)
        assertEquals(Instant.parse("2026-11-08T12:00:00Z"), users.findById(checkNotNull(saved.id)).orElseThrow().createdAt)
    }

    @Test
    fun `a duplicate email is recognized as a unique violation on users_email`() {
        val email = uniqueEmail()
        users.save(User(name = "Carol", email = email, createdAt = Instant.now()))

        val error = assertFailsWith<DataAccessException> {
            users.save(User(name = "Carol 2", email = email, createdAt = Instant.now()))
        }
        assertTrue(error.isUniqueViolation("users.email"), "not recognized: $error")
    }

    private fun uniqueEmail() = "persistence-${UUID.randomUUID()}@example.com"
}
