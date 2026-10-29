package io.polion.desktop

import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.bitcoin.PacedEsploraTransport
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Owner
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCloseTest {
    @Test
    fun closeCancelsPacedWaitAndKeepsWorkerDatabaseOpenUntilCleanupFinishes() {
        val host = DesktopHost(Files.createTempDirectory("polion-host-close-"), false)
        val waiting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            host.runSync(SyncStatus(SyncPhase.SYNCING, "Test"), {}) { application ->
                try {
                    val transport =
                        PacedEsploraTransport(
                            delegate = EsploraTransport { throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED) },
                            nowMillis = { System.nanoTime() / 1_000_000 },
                            sleepMillis = {
                                try {
                                    Thread.sleep(it)
                                } catch (_: InterruptedException) {
                                    throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                                }
                            },
                            ensureActive = {
                                if (Thread.currentThread().isInterrupted) throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                            },
                            onRetry = { waiting.countDown() },
                        )
                    try {
                        transport.get("/blocks/tip/hash")
                        error("Closing the host must cancel the request")
                    } catch (cancelled: BitcoinProviderException) {
                        assertEquals(BitcoinProviderFailure.CANCELLED, cancelled.reason)
                        Thread.interrupted()
                        application.saveOwner(Owner(OwnerId("cleanup"), "Worker cleanup"))
                        assertEquals(
                            listOf(OwnerId("cleanup")),
                            application
                                .snapshot()
                                .ownership.owners
                                .map { it.id },
                        )
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    finished.countDown()
                }
                SyncStatus(SyncPhase.SUCCESS, "Complete")
            }
            assertTrue(waiting.await(10, TimeUnit.SECONDS))
            host.close()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            host.close()
        }
    }
}
