package io.valroot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Clock

/** Projection access is separate from host operations, which retain their platform-owned lifetime. */
internal interface ProductQueries {
    fun reload()

    fun snapshot(filter: ActivityFilter): PortfolioSnapshot

    fun history(
        times: List<Long>,
        portfolio: PortfolioId?,
    ): List<HistoryPoint>

    fun firstHistoryActivity(portfolio: PortfolioId?): Long? = null

    /** The chart's currency without building a whole snapshot. */
    fun currency(portfolio: PortfolioId?): io.valroot.core.model.AssetId = snapshot(ActivityFilter(portfolio = portfolio)).currency
}

internal class ProductPresenter(
    initial: ProductPresentation,
    private val queries: ProductQueries,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    var state by mutableStateOf(initial)
        private set
    private var cacheRevision: Long? = null
    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    val messages = messageChannel.receiveAsFlow()
    private var snapshotRequest = 0L
    private var chartRequest = 0L
    private var activeMutations = 0

    private data class ChartKey(
        val portfolio: PortfolioId?,
        val period: Int,
        val currency: AssetId?,
        val revision: Int,
        val hostRevision: Long,
    )

    private data class ChartSelection(
        val portfolio: PortfolioId?,
        val period: Int,
        val currency: AssetId?,
    )

    private data class CachedChart(
        val at: Long,
        val points: List<HistoryPoint>,
        val currency: AssetId,
    )

    private val charts = mutableMapOf<ChartKey, CachedChart>()

    fun dispatch(event: ProductEvent) {
        if (event is ProductEvent.Portfolio && event.value != state.selectedPortfolio) snapshotRequest++
        update { reduce(event) }
    }

    fun update(change: ProductPresentation.() -> ProductPresentation) {
        val previous = state
        val next = previous.change()
        val previousSelection = ChartSelection(previous.selectedPortfolio, previous.period, previous.snapshot?.currency)
        val nextSelection = ChartSelection(next.selectedPortfolio, next.period, next.snapshot?.currency)
        if (previousSelection != nextSelection) {
            chartRequest++
            state =
                next.copy(
                    chart = emptyList(),
                    chartCurrency = null,
                    chartLoading = next.showsHistory,
                    chartError = false,
                )
        } else {
            state = next
        }
    }

    fun notify(message: String) {
        messageChannel.trySend(message)
    }

    fun execute(
        success: String = "Saved on this device",
        action: () -> Unit,
    ) {
        scope.launch {
            activeMutations++
            update { copy(busy = true) }
            try {
                withContext(dispatcher) { action() }
                update { copy(revision = revision + 1) }
                notify(success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notify("Could not save. Check your input and available storage, then try again.")
            } finally {
                activeMutations--
                update { copy(busy = activeMutations > 0) }
            }
        }
    }

    private fun invalidate(revision: Long) {
        if (cacheRevision != revision) {
            queries.reload()
            cacheRevision = revision
        }
    }

    suspend fun loadSnapshot(hostRevision: Long) {
        val request = ++snapshotRequest
        val filter = state.requestedFilter()
        update { copy(snapshotLoading = true) }
        try {
            val loaded =
                withContext(dispatcher) {
                    invalidate(hostRevision)
                    queries.snapshot(filter)
                }
            if (request == snapshotRequest) update { copy(snapshot = loaded, snapshotFilter = filter) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (request == snapshotRequest) notify("Your data could not be loaded. Check storage and reopen the app.")
        } finally {
            if (request == snapshotRequest) update { copy(snapshotLoading = false) }
        }
    }

    suspend fun loadChart(hostRevision: Long) {
        val request = ++chartRequest
        val selected = state
        if (!selected.showsHistory) {
            update { copy(chartLoading = false) }
            return
        }
        if (selected.snapshot == null || selected.snapshotFilter.portfolio != selected.selectedPortfolio) {
            update { copy(chart = emptyList(), chartCurrency = null, chartLoading = false, chartError = true) }
            return
        }
        val key = ChartKey(selected.selectedPortfolio, selected.period, selected.snapshot?.currency, selected.revision, hostRevision)
        val currentTime = now()
        val cached = charts[key]
        if (cached != null &&
            cached.currency == selected.snapshot?.currency &&
            currentTime >= cached.at &&
            currentTime - cached.at < CHART_CACHE_MILLIS
        ) {
            update { copy(chart = cached.points, chartCurrency = cached.currency, chartLoading = false, chartError = false) }
            return
        }
        prefetch?.cancel()
        update { copy(chartLoading = true, chartError = false) }
        try {
            val started = now()
            val loaded = withContext(dispatcher) { computeChart(selected.period, selected.selectedPortfolio, hostRevision) }
            if (request == chartRequest) {
                cacheChart(key, loaded)
                update { copy(chart = loaded.second, chartCurrency = loaded.first) }
                println("valroot:CHART_LOADED period=${selected.period} ms=${now() - started}")
                // Other ranges load in the background, one at a time, so switching to them is immediate.
                prefetch =
                    scope.launch {
                        for (period in listOf(1, 7, 30, -1, 90, -2, 365, 0)) {
                            if (request != chartRequest) break
                            val other = key.copy(period = period)
                            if (period == selected.period || charts[other]?.let { now() - it.at < CHART_CACHE_MILLIS } == true) continue
                            val points =
                                runCatching {
                                    withContext(dispatcher) { computeChart(period, selected.selectedPortfolio, hostRevision) }
                                }.getOrNull() ?: break
                            if (request == chartRequest) cacheChart(other, points)
                        }
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (request == chartRequest) {
                update { copy(chartError = true) }
                notify("Historical values could not be loaded. Saved balances are still available.")
            }
        } finally {
            if (request == chartRequest) update { copy(chartLoading = false) }
        }
    }

    private var prefetch: kotlinx.coroutines.Job? = null

    private fun cacheChart(
        key: ChartKey,
        loaded: Pair<io.valroot.core.model.AssetId, List<HistoryPoint>>,
    ) {
        if (charts.size >= 12 && key !in charts) charts.remove(charts.keys.first())
        charts[key] = CachedChart(now(), loaded.second, loaded.first)
    }

    /** Samples the range ending now; runs on the database dispatcher. */
    private fun computeChart(
        period: Int,
        portfolio: PortfolioId?,
        hostRevision: Long,
    ): Pair<io.valroot.core.model.AssetId, List<HistoryPoint>> {
        invalidate(hostRevision)
        val end = now()
        val start = historyRangeStart(period, end, if (period == 0) queries.firstHistoryActivity(portfolio) else null)
        // Enough samples for a smooth line and about two dozen candles of four samples each.
        val intervals =
            when (period) {
                -1 -> 60
                1 -> 96
                7 -> 84
                30 -> 90
                90 -> 90
                else -> 104
            }
        val currency = queries.currency(portfolio)
        return currency to
            queries.history(
                (0..intervals).map { start + (end - start) / intervals * it + ((end - start) % intervals) * it / intervals },
                portfolio,
            )
    }

    /** Bind existing rendering callbacks without giving them ownership of the screen state. */
    fun <T> field(
        read: (ProductPresentation) -> T,
        write: (T) -> Unit,
    ): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(
                thisRef: Any?,
                property: KProperty<*>,
            ): T = read(state)

            override fun setValue(
                thisRef: Any?,
                property: KProperty<*>,
                value: T,
            ) = write(value)
        }
}

@Composable
internal fun rememberProductPresenter(
    app: PortfolioApplication,
    host: PlatformActions,
): ProductPresenter {
    val scope = rememberCoroutineScope()
    val presenter =
        rememberSaveable(
            app,
            host,
            saver =
                listSaver(
                    save = {
                        listOf(
                            it.state.page.name,
                            it.state.search,
                            it.state.offset,
                            it.state.period,
                            it.state.dialog
                                ?.key
                                .orEmpty(),
                            it.state.activityStatus
                                ?.name
                                .orEmpty(),
                            it.state.activityNetwork.orEmpty(),
                            it.state.activityAsset
                                ?.value
                                .orEmpty(),
                            it.state.selectedPortfolio
                                ?.value
                                .orEmpty(),
                            it.state.detailAsset
                                ?.value
                                .orEmpty(),
                            it.state.kind
                                ?.name
                                .orEmpty(),
                            it.state.selectedAccount
                                ?.value
                                .orEmpty(),
                        )
                    },
                    restore = {
                        ProductPresenter(
                            ProductPresentation(
                                page = Page.valueOf(it[0] as String),
                                search = it[1] as String,
                                offset = it[2] as Int,
                                period = it[3] as Int,
                                dialog = ProductDialog.fromKey((it[4] as String).ifEmpty { null }),
                                activityStatus =
                                    (it.getOrNull(5) as? String)
                                        ?.takeIf { value -> value.isNotEmpty() }
                                        ?.let(io.valroot.core.ledger.ActivityStatus::valueOf),
                                activityNetwork = (it.getOrNull(6) as? String)?.ifEmpty { null },
                                activityAsset =
                                    (it.getOrNull(7) as? String)
                                        ?.takeIf { value ->
                                            value.isNotEmpty()
                                        }?.let { value ->
                                            io.valroot.core.model
                                                .AssetId(value)
                                        },
                                selectedPortfolio =
                                    (it.getOrNull(8) as? String)
                                        ?.takeIf { value -> value.isNotEmpty() }
                                        ?.let { value ->
                                            io.valroot.core.model
                                                .PortfolioId(value)
                                        },
                                detailAsset =
                                    (it.getOrNull(9) as? String)?.takeIf(String::isNotEmpty)?.let { value ->
                                        io.valroot.core.model
                                            .AssetId(value)
                                    },
                                kind =
                                    (
                                        it.getOrNull(
                                            10,
                                        ) as? String
                                    )?.takeIf(String::isNotEmpty)?.let(io.valroot.core.ledger.ActivityKind::valueOf),
                                selectedAccount =
                                    (it.getOrNull(11) as? String)?.takeIf(String::isNotEmpty)?.let { value ->
                                        io.valroot.core.model
                                            .AccountId(value)
                                    },
                            ),
                            applicationQueries(app),
                            host.dispatcher,
                            scope,
                            now = app::evaluationTimeMillis,
                        )
                    },
                ),
        ) { ProductPresenter(ProductPresentation(), applicationQueries(app), host.dispatcher, scope, now = app::evaluationTimeMillis) }
    val hostRevision by host.dataRevision.collectAsState()
    val state = presenter.state
    val filter = state.requestedFilter()
    LaunchedEffect(presenter, state.page, state.revision, hostRevision, filter) {
        presenter.loadSnapshot(hostRevision)
    }
    LaunchedEffect(
        presenter,
        state.page,
        state.revision,
        hostRevision,
        state.selectedPortfolio,
        state.period,
        state.snapshot?.currency,
        state.snapshotFilter.portfolio,
    ) {
        presenter.loadChart(hostRevision)
    }
    return presenter
}

private fun applicationQueries(app: PortfolioApplication): ProductQueries =
    object : ProductQueries {
        override fun reload() = app.reload()

        override fun snapshot(filter: ActivityFilter) = app.snapshot(filter)

        override fun history(
            times: List<Long>,
            portfolio: PortfolioId?,
        ) = app.historySeries(times, portfolio)

        override fun firstHistoryActivity(portfolio: PortfolioId?) = app.firstHistoryActivity(portfolio)

        override fun currency(portfolio: PortfolioId?) = app.displayCurrency(portfolio)
    }

/** Charts depend on the data revision as well, so a cached range is reused for at most five minutes. */
private const val CHART_CACHE_MILLIS = 300_000L
