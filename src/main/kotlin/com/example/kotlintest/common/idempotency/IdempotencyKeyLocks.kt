package com.example.kotlintest.common.idempotency

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The idempotency keys whose requests are being processed right now in this JVM.
 *
 * A request that finds its key already held gets `409 Request in progress`. This check needs no database access,
 * so concurrent duplicates never compete with the running request for SQLite locks. The `UNIQUE` constraint on
 * `idempotency_keys.idempotency_key` is the backstop across JVMs (see [IdempotentExecutor]).
 */
@Component
class IdempotencyKeyLocks {

    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Returns `true` if the key was free and is now held by the caller. */
    fun tryAcquire(key: String): Boolean = held.add(key)

    fun release(key: String) {
        held.remove(key)
    }
}
