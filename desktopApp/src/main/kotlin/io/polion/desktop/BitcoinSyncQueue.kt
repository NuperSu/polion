package io.polion.desktop

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs network-bound Bitcoin work on its own thread, apart from the UI database queue.
 * At most one task runs at a time; a task is rejected while another is active or queued.
 */
internal class BitcoinSyncQueue(
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "polion-bitcoin-sync").apply { isDaemon = true }
        },
) {
    private val active = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var closed = false

    val busy: Boolean get() = active.get()

    /** Returns false without running [task] when a sync is already active or queued. */
    fun tryStart(task: () -> Unit): Boolean {
        synchronized(lifecycleLock) {
            if (closed || !active.compareAndSet(false, true)) return false
            try {
                executor.execute {
                    try {
                        task()
                    } finally {
                        active.set(false)
                    }
                }
            } catch (_: RejectedExecutionException) {
                active.set(false)
                return false
            }
        }
        return true
    }

    /** Reserves the sync slot for a database replacement on the caller's worker queue. */
    fun tryRunExclusive(task: () -> Unit): Boolean {
        synchronized(lifecycleLock) {
            if (closed || !active.compareAndSet(false, true)) return false
        }
        try {
            task()
        } finally {
            active.set(false)
        }
        return true
    }

    fun shutdown() {
        synchronized(lifecycleLock) {
            closed = true
            if (executor.shutdownNow().isNotEmpty()) active.set(false)
        }
    }

    fun awaitTermination(timeoutMillis: Long): Boolean = executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
}
