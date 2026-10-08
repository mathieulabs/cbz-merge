package com.cbzmerge.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Limits how much memory parallel work may use at once.
 * A task asking for more than is free waits until other tasks are done.
 */
class MemoryBudget(private val limit: Long) {
    private val lock = Mutex()
    private var used = 0L

    suspend fun <T> reserve(bytes: Long, block: suspend () -> T): T {
        val need = bytes.coerceIn(0, limit)
        while (!lock.withLock { (used + need <= limit).also { if (it) used += need } }) delay(10)
        try {
            return block()
        } finally {
            lock.withLock { used -= need }
        }
    }
}
