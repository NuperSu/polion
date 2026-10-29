package io.polion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.ActivityFilter
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.model.AssetId
import io.polion.core.model.TrackingTargetId
import io.polion.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProductPresenterLifecycleTest {
    @Test
    fun compositionRecreationRestoresSavedNavigationAndCreatesFreshPresenter() {
        JvmSqliteDatabase(":memory:").use { db ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val application = PortfolioApplication(db) { 1L }
                val host = Host(dispatcher)
                val registry = mutableStateOf(SaveableStateRegistry(null) { true })
                val visible = mutableStateOf(true)
                var savedValues: Map<String, List<Any?>> = emptyMap()
                lateinit var presenter: ProductPresenter
                runDesktopComposeUiTest {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                                presenter = rememberProductPresenter(application, host)
                            }
                        }
                    }
                    runOnIdle {
                        presenter.dispatch(ProductEvent.Navigate(Page.REPORTS))
                        presenter.dispatch(ProductEvent.Search("funding"))
                        presenter.dispatch(ProductEvent.Offset(50))
                        presenter.dispatch(ProductEvent.Period(90))
                        presenter.dispatch(ProductEvent.Dialog(ProductDialog.BACKUP))
                    }
                    waitForIdle()
                    val original = presenter
                    runOnIdle {
                        savedValues = registry.value.performSave()
                        visible.value = false
                    }
                    waitForIdle()
                    runOnIdle {
                        registry.value = SaveableStateRegistry(savedValues) { true }
                        visible.value = true
                    }
                    waitForIdle()
                    runOnIdle {
                        assertNotSame(original, presenter)
                        assertEquals(Page.REPORTS, presenter.state.page)
                        assertEquals("funding", presenter.state.search)
                        assertEquals(50, presenter.state.offset)
                        assertEquals(90, presenter.state.period)
                        assertEquals(ProductDialog.BACKUP, presenter.state.dialog)
                    }
                }
            }
        }
    }

    @Test
    fun overlappingMutationsKeepBusyUntilLastQueuedActionCompletes() =
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                    val queries = Queries(PortfolioApplication(db) { 1L }.snapshot())
                    val presenter = ProductPresenter(ProductPresentation(), queries, dispatcher, this)
                    val firstStarted = CountDownLatch(1)
                    val firstRelease = CountDownLatch(1)
                    val secondStarted = CountDownLatch(1)
                    val secondRelease = CountDownLatch(1)
                    try {
                        presenter.execute("First saved") {
                            firstStarted.countDown()
                            check(firstRelease.await(5, TimeUnit.SECONDS))
                        }
                        presenter.execute("Second saved") {
                            secondStarted.countDown()
                            check(secondRelease.await(5, TimeUnit.SECONDS))
                        }
                        withContext(Dispatchers.IO) { assertTrue(firstStarted.await(3, TimeUnit.SECONDS)) }
                        assertTrue(presenter.state.busy)
                        firstRelease.countDown()
                        withContext(Dispatchers.IO) { assertTrue(secondStarted.await(3, TimeUnit.SECONDS)) }
                        withTimeout(3000) { while (presenter.state.revision < 1) delay(1) }
                        assertTrue(presenter.state.busy)
                        assertEquals(1, presenter.state.revision)
                        secondRelease.countDown()
                        withTimeout(3000) { while (presenter.state.busy) delay(1) }
                        assertEquals(2, presenter.state.revision)
                        assertEquals(listOf("First saved", "Second saved"), presenter.messages.take(2).toList())
                    } finally {
                        firstRelease.countDown()
                        secondRelease.countDown()
                    }
                }
            }
        }

    @Test
    fun queuedMessagesKeepOrderWhenSnackbarCollectionRestarts() =
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                val presenter =
                    ProductPresenter(
                        ProductPresentation(),
                        Queries(PortfolioApplication(db) { 1L }.snapshot()),
                        Dispatchers.Unconfined,
                        this,
                    )
                presenter.notify("First")
                presenter.notify("Second")
                presenter.notify("Third")
                assertEquals("First", presenter.messages.first())
                assertEquals(listOf("Second", "Third"), presenter.messages.take(2).toList())
                val waiting = launch(start = CoroutineStart.UNDISPATCHED) { presenter.messages.collect { } }
                // The collector is cancelled while waiting, before any new snackbar notification exists.
                waiting.cancelAndJoin()
                presenter.notify("Fourth")
                assertEquals("Fourth", presenter.messages.first())
            }
        }

    @Test
    fun cancelledSnapshotNeverPublishesStaleFilterOrAnErrorNotification() =
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                    val accepted = PortfolioApplication(db) { 1L }.snapshot()
                    val queries = Queries(accepted)
                    val presenter =
                        ProductPresenter(ProductPresentation(page = Page.ACTIVITY, snapshot = accepted), queries, dispatcher, this)
                    val started = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val messages = mutableListOf<String>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) { presenter.messages.collect { messages.add(it) } }
                    queries.beforeSnapshot = {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    val stale = launch(start = CoroutineStart.UNDISPATCHED) { presenter.loadSnapshot(1) }
                    try {
                        withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                        presenter.dispatch(ProductEvent.Search("fresh"))
                        stale.cancel()
                        release.countDown()
                        stale.join()
                        queries.beforeSnapshot = {}
                        presenter.loadSnapshot(1)
                        assertEquals("fresh", presenter.state.snapshotFilter.search)
                        assertEquals(accepted, presenter.state.snapshot)
                        assertFalse(presenter.state.snapshotLoading)
                        assertTrue(messages.isEmpty())
                    } finally {
                        release.countDown()
                        stale.cancelAndJoin()
                        collector.cancelAndJoin()
                    }
                }
            }
        }

    private class Queries(
        private val snapshot: PortfolioSnapshot,
    ) : ProductQueries {
        var beforeSnapshot: () -> Unit = {}

        override fun reload() = Unit

        override fun snapshot(filter: ActivityFilter): PortfolioSnapshot {
            beforeSnapshot()
            return snapshot
        }

        override fun history(
            times: List<Long>,
            portfolio: io.polion.core.model.PortfolioId?,
        ): List<HistoryPoint> = emptyList()
    }

    private class Host(
        override val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) : PlatformActions {
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val hasLock = false

        override fun saveFile(
            name: String,
            bytes: ByteArray,
            onResult: (String) -> Unit,
        ) = Unit

        override fun openFile(onResult: (ByteArray?) -> Unit) = Unit

        override fun restoreBackup(
            bytes: ByteArray,
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun verifyAccountingOrder(onResult: (String) -> Unit) = Unit

        override fun discoverWallet(
            walletId: TrackingTargetId,
            onResult: (String) -> Unit,
        ) = Unit

        override fun fetchHistoricalPrice(
            asset: AssetId,
            quote: AssetId,
            dayUtcMillis: Long,
            apiKey: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun refresh(onResult: (String) -> Unit) = Unit

        override fun recoverBitcoinHistory(onResult: (String) -> Unit) = Unit

        override fun setBackgroundRefresh(enabled: Boolean) = Unit

        override fun backgroundRefreshEnabled() = false

        override fun lock() = Unit

        override fun unlock(
            password: String,
            onResult: (UnlockResult) -> Unit,
        ) = Unit

        override fun setLockPassword(
            password: String,
            onResult: (String) -> Unit,
        ) = Unit

        override fun removeLockPassword(onResult: (String) -> Unit) = Unit
    }
}
