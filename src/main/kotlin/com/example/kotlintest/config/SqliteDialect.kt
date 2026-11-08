package com.example.kotlintest.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.dialect.JdbcArrayColumns
import org.springframework.data.jdbc.core.dialect.JdbcDialect
import org.springframework.data.relational.core.dialect.AnsiDialect
import org.springframework.data.relational.core.dialect.LimitClause
import org.springframework.data.relational.core.dialect.LockClause
import org.springframework.data.relational.core.sql.LockOptions

/**
 * Spring Data JDBC has no built-in SQLite dialect (startup fails with `NoDialectException`).
 *
 * Based on [AnsiDialect], with SQLite-specific differences:
 * - paging uses `LIMIT n OFFSET m` instead of `OFFSET .. FETCH FIRST ..`;
 * - SQLite has no row-level locks, so `SELECT ... FOR UPDATE` renders as nothing
 *   (the whole database is locked by a write transaction);
 * - no array column support.
 */
object SqliteDialect : AnsiDialect(), JdbcDialect {

    private val limitClause = object : LimitClause {
        override fun getLimit(limit: Long) = "LIMIT $limit"
        override fun getOffset(offset: Long) = "LIMIT -1 OFFSET $offset"
        override fun getLimitOffset(limit: Long, offset: Long) = "LIMIT $limit OFFSET $offset"
        override fun getClausePosition() = LimitClause.Position.AFTER_ORDER_BY
    }

    private val lockClause = object : LockClause {
        override fun getLock(lockOptions: LockOptions) = ""
        override fun getClausePosition() = LockClause.Position.AFTER_ORDER_BY
    }

    override fun getName(): String = "SQLite"

    override fun limit(): LimitClause = limitClause

    override fun lock(): LockClause = lockClause

    override fun getArraySupport(): JdbcArrayColumns = JdbcArrayColumns.unsupported()
}

@Configuration(proxyBeanMethods = false)
class SqliteDialectConfig {

    /** Replaces Boot's auto-detected `jdbcDialect` bean, which is `@ConditionalOnMissingBean`. */
    @Bean
    fun jdbcDialect(): JdbcDialect = SqliteDialect
}
