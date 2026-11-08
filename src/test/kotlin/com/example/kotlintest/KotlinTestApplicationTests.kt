package com.example.kotlintest

import com.example.kotlintest.config.SqliteDialect
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jdbc.core.dialect.JdbcDialect
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertSame

@SpringBootTest
@ActiveProfiles("test")
class KotlinTestApplicationTests(@Autowired private val dialect: JdbcDialect) {

    @Test
    fun `context loads with the SQLite dialect`() {
        assertSame(SqliteDialect, dialect)
    }
}
