package io.polion.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitcoinSyncQueueTest {
    @Test
    fun secondSyncIsRefusedWhileFirstIsActiveAndSlotFreesAfterwards() {
        val queue = BitcoinSyncQueue(Executors.newSingleThreadExecutor())
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val ran = mutableListOf<String>()
        assertTrue(
            queue.tryStart {
                ran.add("first")
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            },
        )
        started.await(10, TimeUnit.SECONDS)
        assertTrue(queue.busy)
        assertFalse(queue.tryStart { ran.add("refused") })
        release.countDown()
        awaitIdle(queue)
        assertFalse(queue.busy)
        assertTrue(queue.tryStart { ran.add("second") })
        awaitIdle(queue)
        assertEquals(listOf("first", "second"), ran)
        queue.shutdown()
    }

    private fun awaitIdle(queue: BitcoinSyncQueue) {
        repeat(1000) {
            if (!queue.busy) return
            Thread.sleep(10)
        }
    }
}
