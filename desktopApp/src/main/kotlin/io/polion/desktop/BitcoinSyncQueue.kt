package io.polion.desktop

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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

    val busy: Boolean get() = active.get()

    /** Returns false without running [task] when a sync is already active or queued. */
    fun tryStart(task: () -> Unit): Boolean {
        if (!active.compareAndSet(false, true)) return false
        try {
            executor.execute {
                try {
                    task()
                } finally {
                    active.set(false)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            active.set(false)
            throw rejected
        }
        return true
    }

    /** Reserves the sync slot for a database replacement on the caller's worker queue. */
    fun tryRunExclusive(task: () -> Unit): Boolean {
        if (!active.compareAndSet(false, true)) return false
        try {
            task()
        } finally {
            active.set(false)
        }
        return true
    }

    fun shutdown() {
        executor.shutdown()
    }
}
