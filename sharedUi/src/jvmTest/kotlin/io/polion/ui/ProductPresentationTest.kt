package io.polion.ui

import io.polion.core.application.ActivityFilter
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.ledger.ActivityKind
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.PortfolioId
import io.polion.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductPresentationTest {
    @Test
    fun chartCacheRespectsScopeRevisionsAndExpiry() =
        runBlocking {
            var time = 100_000_000L
            var calls = 0
            var snapshot = JvmSqliteDatabase(":memory:").use { PortfolioApplication(it) { time }.snapshot() }
            var failSnapshot = false
            val requestedScopes = mutableListOf<PortfolioId?>()
            val queries =
                object : ProductQueries {
                    override fun reload() = Unit

                    override fun snapshot(filter: ActivityFilter): PortfolioSnapshot {
                        check(!failSnapshot)
                        requestedScopes += filter.portfolio
                        return snapshot
                    }

                    override fun history(
                        times: List<Long>,
                        portfolio: PortfolioId?,
                    ): List<HistoryPoint> {
                        calls++
                        return listOf(HistoryPoint(times.last(), emptyList()))
                    }
                }
            val presenter =
                ProductPresenter(
                    ProductPresentation(page = Page.OVERVIEW, snapshot = snapshot),
                    queries,
                    kotlinx.coroutines.Dispatchers.Unconfined,
                    this,
                ) { time }
            presenter.loadChart(1)
            val original = presenter.state.chart
            presenter.dispatch(ProductEvent.Navigate(Page.ACCOUNTS))
            presenter.loadChart(1)
            presenter.dispatch(ProductEvent.Navigate(Page.OVERVIEW))
            presenter.loadChart(1)
            assertEquals(1, calls)
            assertEquals(original, presenter.state.chart)
            assertFalse(presenter.state.chartLoading)
            presenter.dispatch(ProductEvent.Period(7))
            presenter.loadChart(1)
            presenter.dispatch(ProductEvent.Period(30))
            presenter.loadChart(1)
            assertEquals(2, calls)
            presenter.update { copy(selectedPortfolio = PortfolioId("other")) }
            assertTrue(presenter.state.chart.isEmpty())
            assertNull(presenter.state.chartCurrency)
            presenter.loadChart(1)
            assertEquals(2, calls)
            presenter.loadSnapshot(1)
            presenter.loadChart(1)
            assertEquals(3, calls)
            assertEquals(PortfolioId("other"), requestedScopes.last())
            presenter.update { copy(revision = revision + 1) }
            presenter.loadChart(1)
            presenter.loadChart(2)
            assertEquals(5, calls)
            // Cached ranges expire after five minutes.
            time += 299_999
            presenter.loadChart(2)
            assertEquals(5, calls)
            time += 1
            presenter.loadChart(2)
            assertEquals(6, calls)
            time -= 600_000
            presenter.loadChart(2)
            assertEquals(7, calls)
            snapshot = snapshot.copy(currency = AssetId("EUR"))
            presenter.update { copy(snapshot = snapshot) }
            presenter.loadChart(2)
            assertEquals(8, calls)
            assertEquals(AssetId("EUR"), presenter.state.chartCurrency)
            val retained = presenter.state.chart
            failSnapshot = true
            presenter.loadChart(3)
            assertTrue(presenter.state.chartError)
            assertEquals(retained, presenter.state.chart)
            assertEquals(AssetId("EUR"), presenter.state.chartCurrency)
        }

    @Test
    fun assetDrilldownResetsUnrelatedFiltersButPreservesPortfolio() {
        val portfolio = PortfolioId("portfolio")
        val state =
            ProductPresentation(
                page = Page.OVERVIEW,
                selectedPortfolio = portfolio,
                search = "old",
                kind = ActivityKind.TRANSFER,
                selectedAccount = AccountId("old"),
                offset = 50,
            ).reduce(ProductEvent.AssetActivity(AssetId("BTC")))
        assertEquals(Page.ACTIVITY, state.page)
        assertEquals(ActivityFilter(asset = AssetId("BTC"), portfolio = portfolio, includeNetworkChoices = true), state.requestedFilter())
        assertNull(state.reduce(ProductEvent.ClearActivityAsset).requestedFilter().asset)
        assertEquals(ActivityFilter(portfolio = portfolio), state.reduce(ProductEvent.Navigate(Page.OVERVIEW)).requestedFilter())
    }

    @Test
    fun chartRangesUseScopedFirstActivityAndIncludeEndpoints() =
        runBlocking {
            var requested = emptyList<Long>()
            var scope: PortfolioId? = null
            val first = 1_000L
            val end = 200_000_000L
            val snapshot = JvmSqliteDatabase(":memory:").use { PortfolioApplication(it) { end }.snapshot() }
            val queries =
                object : ProductQueries {
                    override fun reload() = Unit

                    override fun snapshot(filter: ActivityFilter): PortfolioSnapshot {
                        assertEquals(PortfolioId("selected"), filter.portfolio)
                        return snapshot
                    }

                    override fun firstHistoryActivity(portfolio: PortfolioId?): Long? {
                        scope = portfolio
                        return first
                    }

                    override fun history(
                        times: List<Long>,
                        portfolio: PortfolioId?,
                    ): List<HistoryPoint> {
                        requested = times
                        assertEquals(scope, portfolio)
                        return emptyList()
                    }
                }
            val portfolio = PortfolioId("selected")
            val presenter =
                ProductPresenter(
                    ProductPresentation(page = Page.OVERVIEW, selectedPortfolio = portfolio, period = 0),
                    queries,
                    kotlinx.coroutines.Dispatchers.Unconfined,
                    this,
                ) {
                    end
                }
            presenter.loadSnapshot(0)
            presenter.loadChart(0)
            assertEquals(portfolio, scope)
            assertEquals(first, requested.first())
            assertEquals(end, requested.last())
            presenter.dispatch(ProductEvent.Period(1))
            presenter.loadChart(0)
            assertEquals(end - 86_400_000L, requested.first())
            assertEquals(end, requested.last())
            // One day in 15-minute steps: enough for a smooth line and 24 four-sample candles.
            assertEquals(97, requested.size)
            assertEquals(900_000L, requested[1] - requested[0])
        }

    @Test
    fun selectionEventsResetPaginationAndPreserveOtherChoices() {
        val initial =
            ProductPresentation(
                page = Page.ACTIVITY,
                search = "funding",
                kind = ActivityKind.TRANSFER,
                offset = 100,
                selectedPortfolio = PortfolioId("portfolio"),
                period = 90,
                dialog = ProductDialog.SYNC,
            )
        val events =
            listOf(
                ProductEvent.Search("new"),
                ProductEvent.Kind(null),
                ProductEvent.Account(AccountId("account")),
            )
        events.forEach { event ->
            val state = initial.reduce(event)
            assertEquals(0, state.offset)
            assertEquals(initial.selectedPortfolio, state.selectedPortfolio)
            assertEquals(initial.period, state.period)
            assertEquals(ProductDialog.SYNC, state.dialog)
        }
        assertEquals(100, initial.reduce(ProductEvent.Navigate(Page.ACCOUNTS)).offset)
        assertEquals(100, initial.reduce(ProductEvent.Period(7)).offset)
        val filtered = initial.reduce(ProductEvent.Status(io.polion.core.ledger.ActivityStatus.PENDING))
        assertEquals(0, filtered.offset)
        assertEquals(io.polion.core.ledger.ActivityStatus.PENDING, filtered.requestedFilter().status)
        assertEquals(ProductDialog.entries.toSet(), ProductDialog.entries.map { ProductDialog.fromKey(it.key) }.toSet())
    }

    @Test
    fun activityFilterDoesNotLeakIntoOtherScreens() {
        val state =
            ProductPresentation(
                page = Page.ACTIVITY,
                search = "funding",
                offset = 50,
                selectedAccount = AccountId("account"),
                selectedPortfolio = PortfolioId("portfolio"),
            )
        assertEquals("funding", state.requestedFilter().search)
        assertEquals(50, state.requestedFilter().offset)
        assertEquals(
            ActivityFilter(portfolio = state.selectedPortfolio),
            state.reduce(ProductEvent.Navigate(Page.REPORTS)).requestedFilter(),
        )
    }

    @Test
    fun failedReloadRetainsAcceptedSnapshotAndItsFilter() =
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                    val saved = PortfolioApplication(db) { 1L }.snapshot()
                    val queries = Queries(saved)
                    val presenter = ProductPresenter(ProductPresentation(page = Page.ACTIVITY), queries, dispatcher, this)
                    presenter.loadSnapshot(1)
                    presenter.dispatch(ProductEvent.Search("not loaded"))
                    queries.fail = true
                    presenter.loadSnapshot(2)
                    assertEquals(saved, presenter.state.snapshot)
                    assertEquals(ActivityFilter(includeNetworkChoices = true), presenter.state.snapshotFilter)
                    assertEquals("not loaded", presenter.state.search)
                    assertFalse(presenter.state.snapshotLoading)
                    assertEquals(2, queries.reloads)
                }
            }
        }

    @Test
    fun cancelledRequestCannotReplaceNewFilterAndDraftSurvivesReload() =
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                    val saved = PortfolioApplication(db) { 1L }.snapshot()
                    val queries = Queries(saved)
                    val presenter = ProductPresenter(ProductPresentation(page = Page.ACTIVITY), queries, dispatcher, this)
                    val started = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    queries.beforeSnapshot = {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    val first = launch(start = CoroutineStart.UNDISPATCHED) { presenter.loadSnapshot(1) }
                    try {
                        assertTrue(started.await(3, TimeUnit.SECONDS))
                        presenter.dispatch(ProductEvent.Search("latest"))
                        presenter.update { copy(sourceDraftEndpoint = "https://example.test/api", sourceDraftConsent = true) }
                        assertNull(presenter.state.snapshot)
                        assertEquals(ActivityFilter(), presenter.state.snapshotFilter)
                        first.cancel()
                    } finally {
                        release.countDown()
                    }
                    first.join()
                    queries.beforeSnapshot = {}
                    presenter.loadSnapshot(1)
                    assertEquals("latest", presenter.state.snapshotFilter.search)
                    assertEquals(saved, presenter.state.snapshot)
                    assertEquals("https://example.test/api", presenter.state.sourceDraftEndpoint)
                    assertEquals(true, presenter.state.sourceDraftConsent)
                    assertEquals(1, queries.reloads)
                }
            }
        }

    @Test
    fun nonHistoryPageDoesNotReplayHistoryOrDiscardSavedSnapshot() =
        runBlocking {
            JvmSqliteDatabase(":memory:").use { db ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                    val saved = PortfolioApplication(db) { 1L }.snapshot()
                    val queries = Queries(saved)
                    val presenter = ProductPresenter(ProductPresentation(page = Page.ACTIVITY, snapshot = saved), queries, dispatcher, this)
                    presenter.loadChart(1)
                    assertEquals(0, queries.histories)
                    presenter.dispatch(ProductEvent.Navigate(Page.OVERVIEW))
                    presenter.loadChart(1)
                    assertEquals(1, queries.histories)
                    presenter.dispatch(ProductEvent.Search("not part of history scope"))
                    assertEquals(saved, presenter.state.snapshot)
                    assertFalse(presenter.state.chartLoading)
                }
            }
        }

    private class Queries(
        private val saved: PortfolioSnapshot,
    ) : ProductQueries {
        var reloads = 0
        var histories = 0
        var fail = false
        var beforeSnapshot: () -> Unit = {}

        override fun reload() {
            reloads++
        }

        override fun snapshot(filter: ActivityFilter): PortfolioSnapshot {
            beforeSnapshot()
            check(!fail)
            return saved
        }

        override fun history(
            times: List<Long>,
            portfolio: PortfolioId?,
        ): List<HistoryPoint> {
            histories++
            return emptyList()
        }
    }
}
