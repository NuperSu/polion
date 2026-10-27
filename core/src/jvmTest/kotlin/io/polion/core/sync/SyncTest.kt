package io.polion.core.sync

import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryFetcher
import io.polion.core.connectors.HistoryPage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import io.polion.core.model.SyncJobId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTest {
    private class Clock(
        var now: Long = 1000,
    ) : SyncClock {
        override fun nowMillis() = now
    }

    private val job = SyncJob(SyncJobId("job"), SourceId("source"), NetworkId("btc"), AccountId("account"), "overlap", "tip")

    private fun record(id: String) = RawObservation(id, "btc", id, "btc", "source", "2026-10-28T00:00:00Z", id, "1", sha256(id), "{}")

    private fun provider(
        id: String,
        fetch: (HistoryRequest) -> HistoryPage,
    ) = SyncProvider(
        ProviderId(id),
        object : HistoryFetcher {
            override fun fetch(request: HistoryRequest) = fetch(request)
        },
        setOf(NetworkId("btc")),
        setOf(SourceId("source")),
    )

    @Test
    fun resumesPagesAfterReopenAndCommitsObservationsWithCheckpoints() {
        val path = Files.createTempFile("polion-sync", ".sqlite").toString()
        val clock = Clock()
        val fetcher =
            provider("p") { request ->
                assertEquals("overlap", request.overlap)
                assertEquals("tip", request.finality)
                if (request.cursor == null) {
                    HistoryPage(listOf(record("a")), "next", Coverage.PARTIAL)
                } else {
                    HistoryPage(listOf(record("b")), null, Coverage.COMPLETE)
                }
            }
        JvmSqliteDatabase(path).use { db ->
            val sync = SyncOrchestrator(db, Store(db), listOf(fetcher), clock)
            sync.enqueue(job)
            assertTrue(sync.runOne())
            assertEquals("next", sync.status(job.id)?.cursor)
            assertEquals(JobState.READY, sync.status(job.id)?.state)
        }
        JvmSqliteDatabase(path).use { db ->
            val store = Store(db)
            val sync = SyncOrchestrator(db, store, listOf(fetcher), clock)
            assertTrue(sync.runOne())
            assertEquals(JobState.COMPLETED, sync.status(job.id)?.state)
            assertEquals(2, store.records().size)
            assertEquals(2, sync.status(job.id)?.pages)
        }
    }

    @Test
    fun failuresFailOverAndNeverOverwriteDataOrMarkComplete() {
        JvmSqliteDatabase(":memory:").use { db ->
            val store = Store(db)
            val clock = Clock()
            val broken = provider("bad") { throw IllegalStateException("sensitive provider message") }
            val good = provider("good") { HistoryPage(listOf(record("safe")), null, Coverage.COMPLETE) }
            val sync = SyncOrchestrator(db, store, listOf(broken, good), clock)
            sync.enqueue(job)
            assertTrue(sync.runOne())
            assertEquals(JobState.READY, sync.status(job.id)?.state)
            assertTrue(store.records().isEmpty())
            assertFalse(sync.runOne())
            clock.now += 1000
            assertTrue(sync.runOne())
            assertEquals(JobState.COMPLETED, sync.status(job.id)?.state)
            assertEquals(ProviderId("good"), sync.status(job.id)?.lastProvider)
            assertEquals(listOf(listOf("FETCH")), db.query("SELECT kind FROM sync_errors"))
        }
    }

    @Test
    fun badPageRollsBackAllRawAndCheckpointAndPartialIsVisible() {
        JvmSqliteDatabase(":memory:").use { db ->
            val store = Store(db)
            val clock = Clock()
            var invalid = true
            val fetcher =
                provider("p") {
                    if (invalid) {
                        HistoryPage(listOf(record("good"), record("bad").copy(checksum = "wrong")), "next", Coverage.PARTIAL)
                    } else {
                        HistoryPage(emptyList(), null, Coverage.UNKNOWN)
                    }
                }
            val sync = SyncOrchestrator(db, store, listOf(fetcher), clock)
            sync.enqueue(job)
            sync.runOne()
            assertTrue(store.records().isEmpty())
            assertEquals(null, sync.status(job.id)?.cursor)
            assertEquals(0, sync.status(job.id)?.pages)
            invalid = false
            clock.now += 1000
            sync.runOne()
            assertEquals(JobState.PARTIAL, sync.status(job.id)?.state)
            assertEquals(Coverage.UNKNOWN, sync.status(job.id)?.coverage)
        }
    }

    @Test
    fun cancelDuringFetchAndLeaseExpiryPreventStaleWrites() {
        JvmSqliteDatabase(":memory:").use { db ->
            val store = Store(db)
            val clock = Clock()
            lateinit var sync: SyncOrchestrator
            val fetcher =
                provider("p") {
                    sync.cancel(job.id)
                    HistoryPage(listOf(record("ignored")), null, Coverage.COMPLETE)
                }
            sync = SyncOrchestrator(db, store, listOf(fetcher), clock)
            sync.enqueue(job)
            sync.runOne()
            assertEquals(JobState.CANCELLED, sync.status(job.id)?.state)
            assertTrue(store.records().isEmpty())
        }
    }

    @Test
    fun repeatedCursorCyclesAndMismatchedRecordsNeverAdvanceCheckpoint() {
        JvmSqliteDatabase(":memory:").use { db ->
            val store = Store(db)
            val clock = Clock()
            var calls = 0
            val fetcher =
                provider("p") {
                    calls++
                    when (calls) {
                        1 -> HistoryPage(listOf(record("one")), "A", Coverage.PARTIAL)
                        2 -> HistoryPage(listOf(record("two")), "B", Coverage.PARTIAL)
                        3 -> HistoryPage(listOf(record("invalid")), "A", Coverage.PARTIAL)
                        else -> HistoryPage(listOf(record("wrong").copy(network = "other")), null, Coverage.COMPLETE)
                    }
                }
            val sync = SyncOrchestrator(db, store, listOf(fetcher), clock)
            sync.enqueue(job)
            sync.runOne()
            sync.runOne()
            sync.runOne()
            assertEquals("B", sync.status(job.id)?.cursor)
            assertEquals(2, store.records().size)
            clock.now += 1000
            sync.runOne()
            assertEquals("B", sync.status(job.id)?.cursor)
            assertEquals(2, store.records().size)
            assertEquals(JobState.READY, sync.status(job.id)?.state)
        }
    }

    @Test
    fun expiredLeaseReclaimsAndFencesTheOldWorker() {
        JvmSqliteDatabase(":memory:").use { db ->
            val store = Store(db)
            val clock = Clock()
            lateinit var sync: SyncOrchestrator
            var calls = 0
            val fetcher =
                provider("p") {
                    calls++
                    if (calls == 1) {
                        clock.now += 60_001
                        assertTrue(sync.runOne())
                        HistoryPage(listOf(record("stale")), null, Coverage.COMPLETE)
                    } else {
                        HistoryPage(listOf(record("fresh")), null, Coverage.COMPLETE)
                    }
                }
            sync = SyncOrchestrator(db, store, listOf(fetcher), clock)
            sync.enqueue(job)
            sync.runOne()
            assertEquals(JobState.COMPLETED, sync.status(job.id)?.state)
            assertEquals(listOf("fresh"), store.records().map { it[0] })
        }
    }

    @Test
    fun reopenedDatabaseRecoversAbandonedLease() {
        val path = Files.createTempFile("polion-sync-recovery", ".sqlite").toString()
        val clock = Clock()
        val fetcher = provider("p") { HistoryPage(listOf(record("recovered")), null, Coverage.COMPLETE) }
        JvmSqliteDatabase(path).use { db ->
            val sync = SyncOrchestrator(db, Store(db), listOf(fetcher), clock)
            sync.enqueue(job)
            db.execute("UPDATE sync_jobs SET state='RUNNING', token=1, lease_until=1500 WHERE id='job'")
        }
        JvmSqliteDatabase(path).use { db ->
            val sync = SyncOrchestrator(db, Store(db), listOf(fetcher), clock)
            assertFalse(sync.runOne())
            clock.now = 1500
            assertTrue(sync.runOne())
            assertEquals(JobState.COMPLETED, sync.status(job.id)?.state)
        }
    }

    @Test
    fun parallelConnectionsEnforceWorkerLimitAndSameScopeExclusion() {
        val path = Files.createTempFile("polion-sync-concurrency", ".sqlite").toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val clock = Clock()
        val blocking =
            provider("p") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                HistoryPage(emptyList(), null, Coverage.COMPLETE)
            }
        JvmSqliteDatabase(path).use { db1 ->
            JvmSqliteDatabase(path).use { db2 ->
                val first = SyncOrchestrator(db1, Store(db1), listOf(blocking), clock, SyncSettings(maxConcurrent = 2))
                val second = SyncOrchestrator(db2, Store(db2), listOf(blocking), clock, SyncSettings(maxConcurrent = 1))
                first.enqueue(job)
                first.enqueue(job.copy(id = SyncJobId("job2")))
                first.enqueue(job.copy(id = SyncJobId("job3"), account = AccountId("different")))
                val worker =
                    thread {
                        try {
                            first.runOne()
                        } catch (e: Throwable) {
                            failure.set(e)
                        }
                    }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    assertFalse(second.runOne())
                    val permissive =
                        SyncOrchestrator(
                            db2,
                            Store(db2),
                            listOf(
                                provider("other") {
                                    HistoryPage(emptyList(), null, Coverage.COMPLETE)
                                },
                            ),
                            clock,
                            SyncSettings(maxConcurrent = 2),
                        )
                    assertTrue(permissive.runOne())
                    assertEquals(JobState.READY, permissive.status(SyncJobId("job2"))?.state)
                    assertEquals(JobState.COMPLETED, permissive.status(SyncJobId("job3"))?.state)
                } finally {
                    release.countDown()
                    worker.join(10_000)
                }
                assertEquals(null, failure.get())
            }
        }
    }

    @Test
    fun rateLimitSurvivesFailureAndReopen() {
        val path = Files.createTempFile("polion-sync-rate", ".sqlite").toString()
        val clock = Clock()
        var fail = true
        val fetcher =
            provider("p") {
                if (fail) throw IllegalStateException("timeout")
                HistoryPage(emptyList(), null, Coverage.COMPLETE)
            }.copy(minimumIntervalMillis = 5000)
        JvmSqliteDatabase(path).use { db ->
            val sync = SyncOrchestrator(db, Store(db), listOf(fetcher), clock)
            sync.enqueue(job)
            sync.runOne()
            clock.now += 1000
            assertFalse(sync.runOne())
        }
        JvmSqliteDatabase(path).use { db ->
            val sync = SyncOrchestrator(db, Store(db), listOf(fetcher), clock)
            fail = false
            clock.now += 3999
            assertFalse(sync.runOne())
            clock.now++
            assertTrue(sync.runOne())
            assertEquals(JobState.COMPLETED, sync.status(job.id)?.state)
        }
    }
}
