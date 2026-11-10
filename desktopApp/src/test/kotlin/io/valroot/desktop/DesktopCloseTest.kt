package io.valroot.desktop

import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.bitcoin.PacedEsploraTransport
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Owner
import io.valroot.ui.SyncOperation
import io.valroot.ui.SyncPhase
import io.valroot.ui.SyncStatus
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
    fun workerRetainsOperationOnSuccessAndFailure() {
        val host = DesktopHost(Files.createTempDirectory("valroot-operation-status-"), false)
        try {
            val success = CountDownLatch(1)
            host.runSync(SyncStatus(SyncPhase.SYNCING, "Importing prices", SyncOperation.PRICES), { success.countDown() }) {
                SyncStatus(SyncPhase.SUCCESS, "Saved")
            }
            assertTrue(success.await(10, TimeUnit.SECONDS))
            assertEquals(SyncOperation.PRICES, host.syncStatus.value.operation)
            assertEquals(SyncPhase.SUCCESS, host.syncStatus.value.phase)
            val failure = CountDownLatch(1)
            host.runSync(SyncStatus(SyncPhase.SYNCING, "Verifying order", SyncOperation.ORDER), { failure.countDown() }) {
                error("fixture failure")
            }
            assertTrue(failure.await(10, TimeUnit.SECONDS))
            assertEquals(SyncOperation.ORDER, host.syncStatus.value.operation)
            assertEquals(SyncPhase.ERROR, host.syncStatus.value.phase)
        } finally {
            host.close()
        }
    }

    @Test
    fun closeCancelsPacedWaitAndKeepsWorkerDatabaseOpenUntilCleanupFinishes() {
        val host = DesktopHost(Files.createTempDirectory("valroot-host-close-"), false)
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
