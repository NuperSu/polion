package io.polion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.polion.core.application.ActivityFilter
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.model.PortfolioId
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

    fun dispatch(event: ProductEvent) {
        state = state.reduce(event)
    }

    fun update(change: ProductPresentation.() -> ProductPresentation) {
        state = state.change()
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
        update { copy(chart = emptyList(), chartLoading = true) }
        try {
            val loaded =
                withContext(dispatcher) {
                    invalidate(hostRevision)
                    val end = now()
                    val start =
                        if (selected.period == 0) {
                            (queries.firstHistoryActivity(selected.selectedPortfolio) ?: end).coerceIn(0, end)
                        } else {
                            (end - selected.period.toLong() * 86_400_000L).coerceAtLeast(0)
                        }
                    queries.history(
                        (0..12).map { start + (end - start) / 12 * it + ((end - start) % 12) * it / 12 },
                        selected.selectedPortfolio,
                    )
                }
            if (request == chartRequest) update { copy(chart = loaded) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (request == chartRequest) notify("Historical values could not be loaded. Saved balances are still available.")
        } finally {
            if (request == chartRequest) update { copy(chartLoading = false) }
        }
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
                                        ?.let(io.polion.core.ledger.ActivityStatus::valueOf),
                                activityNetwork = (it.getOrNull(6) as? String)?.ifEmpty { null },
                            ),
                            applicationQueries(app),
                            host.dispatcher,
                            scope,
                        )
                    },
                ),
        ) { ProductPresenter(ProductPresentation(), applicationQueries(app), host.dispatcher, scope) }
    val hostRevision by host.dataRevision.collectAsState()
    val state = presenter.state
    val filter = state.requestedFilter()
    LaunchedEffect(presenter, state.page, state.revision, hostRevision, filter) {
        presenter.loadSnapshot(hostRevision)
    }
    LaunchedEffect(presenter, state.page, state.revision, hostRevision, state.selectedPortfolio, state.period) {
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
    }
