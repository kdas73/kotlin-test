package com.example.kotlintest.account

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

enum class AccountKind {
    /** The account of a user. Exactly one per user; its balance is never negative. */
    USER,

    /** System account that money for deposits comes from (STORY-004). */
    FUNDING,

    /** System account that receives payments for orders (STORY-005). */
    REVENUE,
}

/** An account holding money as integer minor units (cents). */
@Table("accounts")
data class Account(
    @Id val id: Long? = null,
    val userId: Long?,
    val kind: AccountKind,
    val balance: Long,
    @Version val version: Long? = null,
    val createdAt: Instant,
)
