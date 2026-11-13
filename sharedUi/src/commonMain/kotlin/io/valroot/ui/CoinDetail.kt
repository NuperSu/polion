package io.valroot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.valroot.core.application.AccountingView
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.Rational
import io.valroot.core.reconcile.EffectiveActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetDialog(
    asset: AssetValuation,
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    host: PlatformActions,
    portfolio: PortfolioId?,
    revision: Int,
    close: () -> Unit,
    price: () -> Unit,
    activity: () -> Unit,
    disposalActivity: (EffectiveActivity) -> Unit,
    useProviderPrices: () -> Unit,
    onAddTransaction: ((AssetId) -> Unit)? = null,
    onRefreshPrices: (() -> Unit)? = null,
) {
    val tracked = data.valuations.any { it.asset == asset.asset }
    var tab by rememberSaveable(asset.asset.value, portfolio?.value) { mutableStateOf("General") }
    var breakdown by rememberSaveable(asset.asset.value) { mutableStateOf(true) }
    var showAccounting by rememberSaveable(asset.asset.value) { mutableStateOf(false) }
    var accounting by remember(app, asset.asset, portfolio) { mutableStateOf<AccountingView?>(null) }
    var accountingError by remember { mutableStateOf(false) }
    val hostRevision by host.dataRevision.collectAsState()
    val priceRefresh by host.priceRefreshStatus.collectAsState()
    LaunchedEffect(app, asset.asset, portfolio, revision, hostRevision) {
        accounting = null
        accountingError = false
        if (!tracked) return@LaunchedEffect
        try {
            accounting = withContext(host.dispatcher) { app.accounting.report(portfolio) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            accountingError = true
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .heightIn(max = 900.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            PullToRefreshBox(
                isRefreshing = priceRefresh == PriceRefreshPhase.RUNNING,
                onRefresh = { if (priceRefresh == PriceRefreshPhase.IDLE) (onRefreshPrices ?: price)() },
            ) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(asset.metadata?.name ?: asset.asset.value, style = MaterialTheme.typography.headlineMedium)
                            Text(asset.metadata?.symbol ?: asset.asset.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = close) { Text("Close") }
                    }
                    Text(
                        portfolio?.let { id ->
                            data.ownership.portfolios
                                .find { it.id == id }
                                ?.label
                        } ?: "All portfolios",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (tracked) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CoinMetric(
                                "Owned",
                                asset.metadata?.let { "${formatUnits(asset.amount.atomicUnits, it.decimals)} ${it.symbol}" }
                                    ?: "${asset.amount.atomicUnits} atomic units",
                                Modifier.weight(1f),
                            )
                            CoinMetric("Market value", asset.value?.let { money(it, data.currency) } ?: "—", Modifier.weight(1f))
                        }
                        if (breakdown) {
                            CoinAccountingSummary(accounting, asset, accountingError)
                        }
                        TextButton(onClick = { breakdown = !breakdown }) {
                            Text(if (breakdown) "Hide cost & gains breakdown" else "Show cost & gains breakdown")
                        }
                    } else {
                        Text("Market data only · no tracked holding", color = Muted)
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("General", "Transactions", "Alerts", "Order book").forEach { name ->
                            TextButton(onClick = { tab = name }, modifier = Modifier.testTag("detail-$name")) {
                                Text(name, fontWeight = if (tab == name) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    HorizontalDivider()
                    when (tab) {
                        "General" -> {
                            Text(
                                asset.price?.let { money(it.price.value, data.currency) } ?: "Price unavailable",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${asset.metadata?.symbol ?: asset.asset.value}/${data.currency.value} · " +
                                    asset.priceStatus.name.lowercase(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            asset.price?.let { observation ->
                                val provider =
                                    if (observation.manual) {
                                        "Manual observation"
                                    } else {
                                        observation.providerId?.value
                                            ?: "Saved observation"
                                    }
                                Text(
                                    provider +
                                        " · observed ${Instant.fromEpochMilliseconds(
                                            observation.observedAtMillis,
                                        )}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            SavedCoinPriceChart(asset.asset, app, host, data.currency, revision, hostRevision)
                            OutlinedButton(
                                onClick = onRefreshPrices ?: price,
                                enabled = priceRefresh == PriceRefreshPhase.IDLE,
                            ) { Text("Refresh prices") }
                            if (priceRefresh != PriceRefreshPhase.IDLE) {
                                Text(
                                    if (priceRefresh ==
                                        PriceRefreshPhase.QUEUED
                                    ) {
                                        "Price refresh queued…"
                                    } else {
                                        "Refreshing prices…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (tracked) AssetAccountBreakdown(asset, data.balances, data.ownership.accounts)
                            if (asset.price?.manual == true) {
                                Text(
                                    "Manual prices override provider prices until you switch back. Saved historical " +
                                        "observations remain available.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(
                                    onClick = useProviderPrices,
                                    modifier = Modifier.testTag("use-provider-prices"),
                                ) { Text("Use provider prices from now") }
                            }
                            Button(onClick = price) { Text("Set a manual price") }
                            if (tracked) {
                                OutlinedButton(
                                    onClick = { showAccounting = !showAccounting },
                                    modifier = Modifier.testTag("load-asset-accounting"),
                                ) {
                                    Text(if (showAccounting) "Hide FIFO lots and disposals" else "View FIFO lots and disposals")
                                }
                                if (showAccounting) AssetAccountingDetails(app, host, asset, data, portfolio, revision, disposalActivity)
                            }
                        }
                        "Transactions" -> {
                            onAddTransaction?.let { add ->
                                Button(
                                    onClick = { add(asset.asset) },
                                    modifier = Modifier.testTag("detail-add-transaction"),
                                ) { Text("New transaction") }
                            }
                            CoinTransactions(asset, app, host, portfolio, revision, hostRevision, disposalActivity)
                            TextButton(onClick = activity) { Text("Open in Activity") }
                        }
                        "Alerts" -> {
                            Text("Price alerts", style = MaterialTheme.typography.titleLarge)
                            Text("Price alerts are not available yet. Saved prices and manual observations are available in General.")
                        }
                        "Order book" -> {
                            Text("Order book", style = MaterialTheme.typography.titleLarge)
                            Text("Order book data is not connected. Valroot tracks public holdings and does not place trades.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoinMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CoinAccountingSummary(
    view: AccountingView?,
    asset: AssetValuation,
    error: Boolean,
) {
    if (view == null) {
        Text(
            if (error) "Cost basis and gains could not be loaded." else "Loading cost basis and gains…",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val lots = view.report.lots.filter { it.asset == asset.asset }
    val disposals = view.report.disposals.filter { it.asset == asset.asset }
    val valuations = view.report.valuations.filter { it.lot.asset == asset.asset }
    val uncertain = view.missingTreatments.isNotEmpty() || view.invalidTreatments.isNotEmpty()

    fun sum(values: List<Rational?>): Rational? =
        if (uncertain ||
            values.any { it == null }
        ) {
            null
        } else {
            values.filterNotNull().fold(Rational.ZERO) { a, b -> a + b }
        }
    val basis = if (lots.isEmpty() && asset.amount.atomicUnits.sign != 0) null else sum(lots.map { it.basis })
    val unrealized = if (valuations.isEmpty() && asset.amount.atomicUnits.sign != 0) null else sum(valuations.map { it.unrealizedGain })
    val realized = sum(disposals.map { it.gain })
    val total = if (unrealized == null || realized == null) null else unrealized + realized

    fun figure(value: Rational?): String = value?.displayAmount() ?: "—"
    val currency = view.report.quoteAsset.value
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CoinMetric("Open cost basis", "${basis?.displayAmount() ?: "—"} $currency", Modifier.weight(1f))
        CoinMetric("Total gains", "${total?.displayAmount() ?: "—"} $currency", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CoinMetric("Unrealized gain", "${unrealized?.displayAmount() ?: "—"} $currency", Modifier.weight(1f))
        CoinMetric("Realized gain", "${realized?.displayAmount() ?: "—"} $currency", Modifier.weight(1f))
    }
    if (uncertain) {
        Text(
            "Classification or correction is needed; cost basis and gains are unknown.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (view.orderingUncertain.isNotEmpty()) {
        Text(
            "FIFO gains are provisional pending transaction order verification.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CoinTransactions(
    asset: AssetValuation,
    app: PortfolioApplication,
    host: PlatformActions,
    portfolio: PortfolioId?,
    revision: Int,
    hostRevision: Long,
    onActivity: (EffectiveActivity) -> Unit,
) {
    var search by rememberSaveable(asset.asset.value, portfolio?.value) { mutableStateOf("") }
    var kindName by rememberSaveable(asset.asset.value, portfolio?.value) { mutableStateOf<String?>(null) }
    var page by rememberSaveable(asset.asset.value, portfolio?.value, search, kindName) { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf<PortfolioSnapshot?>(null) }
    var error by remember { mutableStateOf(false) }
    val filter =
        ActivityFilter(
            search = search,
            portfolio = portfolio,
            asset = asset.asset,
            kind = kindName?.let(ActivityKind::valueOf),
            offset =
                page * 20,
            limit = 20,
        )
    LaunchedEffect(app, filter, revision, hostRevision) {
        error = false
        snapshot = null
        try {
            snapshot = withContext(host.dispatcher) { app.snapshot(filter) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    OutlinedTextField(
        search,
        { search = it },
        label = { Text("Search transactions") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { kindName = null }) { Text(if (kindName == null) "All types ✓" else "All types") }
        ActivityKind.entries.forEach { kind ->
            TextButton(onClick = { kindName = kind.name }) {
                Text(
                    kind.name.lowercase().replace('_', ' ') + if (kindName == kind.name) " ✓" else "",
                )
            }
        }
    }
    when {
        error -> Text("Transactions could not be loaded.")
        snapshot == null -> Text("Loading transactions…")
        else -> {
            val loaded = requireNotNull(snapshot)
            val accounts = activityAccountScope(loaded.ownership, filter)
            if (loaded.activities.isEmpty()) Text("No transactions match this asset and filter.")
            loaded.activities.forEach { item ->
                Surface(
                    onClick = { onActivity(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            item.label ?: item.activity.kind.name
                                .lowercase()
                                .replace('_', ' '),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            Instant.fromEpochMilliseconds(item.activity.occurredAtEpochMillis).toString(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val units = activityRowAmounts(item.activity, accounts).quantities[asset.asset]
                        Text(
                            units?.let { amount ->
                                asset.metadata?.let {
                                    "${io.valroot.core.model.AtomicAmount(
                                        asset.asset,
                                        amount,
                                    ).decimal(it.decimals)} ${it.symbol}"
                                }
                                    ?: "$amount atomic units"
                            }
                                ?: "No net asset movement",
                        )
                        Text(
                            "${item.activity.status.name.lowercase()} · " +
                                "${if (item.original.manual) "Manual entry" else "Imported"} · View details",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
                Text("${loaded.activityTotal} transactions", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { page++ }, enabled = (page + 1) * 20 < loaded.activityTotal) { Text("Next") }
            }
        }
    }
}

@Composable
private fun SavedCoinPriceChart(
    asset: AssetId,
    app: PortfolioApplication,
    host: PlatformActions,
    quote: AssetId,
    revision: Int,
    hostRevision: Long,
) {
    var range by rememberSaveable(asset.value) { mutableStateOf("1M") }
    var values by remember { mutableStateOf<List<Double?>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(app, asset, quote, range, revision, hostRevision) {
        loading = true
        error = false
        try {
            val days =
                when (range) {
                    "1D" -> 1
                    "1W" -> 7
                    "3M" -> 90
                    "1Y" -> 365
                    else -> 30
                }
            val now = Clock.System.now().toEpochMilliseconds()
            val start = now - days * 86_400_000L
            values =
                withContext(host.dispatcher) {
                    app.analytics.priceSeries(asset, quote, (0..30).map { start + (now - start) * it / 30 }).map { point ->
                        point
                            .takeIf { it.priceStatus == io.valroot.core.market.PriceStatus.FRESH }
                            ?.price
                            ?.price
                            ?.value
                            ?.toString()
                            ?.toDoubleOrNull()
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("1D", "1W", "1M", "3M", "1Y").forEach { period ->
            TextButton(onClick = { range = period }) { Text(period + if (period == range) " ✓" else "") }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    if (values.count { it != null } < 2) {
        Box(Modifier.fillMaxWidth().height(180.dp).padding(20.dp)) {
            Text(
                when {
                    loading -> "Loading saved prices…"
                    error -> "Saved prices could not be loaded"
                    else -> "More saved price observations are needed for this period."
                },
            )
        }
    } else {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).semantics {
                contentDescription =
                    "Saved coin price history; gaps indicate unavailable fresh observations"
            },
        ) {
            val available = values.filterNotNull().filter { it.isFinite() }
            if (available.isEmpty()) return@Canvas
            val min = available.min()
            val span = (available.max() - min).takeIf { it > 0 } ?: 1.0
            repeat(3) { row ->
                drawLine(
                    color.copy(alpha = 0.15f),
                    Offset(0f, size.height * row / 2),
                    Offset(
                        size.width,
                        size.height * row / 2,
                    ),
                )
            }
            val path = Path()
            var pen = false
            values.forEachIndexed { index, value ->
                if (value == null || !value.isFinite()) {
                    pen = false
                } else {
                    val x = size.width * index / (values.size - 1).coerceAtLeast(1)
                    val y = (size.height * (0.9 - 0.8 * (value - min) / span)).toFloat()
                    if (pen) path.lineTo(x, y) else path.moveTo(x, y)
                    drawCircle(color, 2.dp.toPx(), Offset(x, y))
                    pen = true
                }
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
    }
    Text(
        "Saved unit prices in portfolio currency. Gaps indicate missing fresh observations; price change is not investment return.",
        style = MaterialTheme.typography.bodySmall,
    )
}
