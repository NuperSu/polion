package io.polion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.application.ActivityFilter
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.application.SourceStatus
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.PortfolioId
import io.polion.core.model.Rounding
import io.polion.core.ownership.AccountKind
import io.polion.core.reconcile.EffectiveActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

private val Ink = Color(0xFF183A36)
private val Muted = Color(0xFF667873)
private val Paper = Color(0xFFF6F7F3)
private val Line = Color(0xFFE1E7E0)
private val Teal = Color(0xFF267969)
private val Mint = Color(0xFFDFF0E7)
private val Orange = Color(0xFFD98A43)
private val Corners = RoundedCornerShape(22.dp)
private val palette =
    lightColorScheme(
        primary = Teal,
        onPrimary = Color.White,
        background = Paper,
        surface = Color.White,
        onSurface = Ink,
        onBackground = Ink,
        outline = Line,
    )

private enum class Page(
    val label: String,
    val glyph: String,
) {
    OVERVIEW("Overview", "◈"),
    ACTIVITY("Activity", "⇄"),
    ACCOUNTS("Accounts", "▣"),
    REPORTS("Reports", "◷"),
    SETTINGS("Settings", "⚙"),
}

@Composable
fun PolionApp(
    application: PortfolioApplication,
    platform: PlatformActions,
    locked: Boolean = false,
) {
    MaterialTheme(colorScheme = palette) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            if (locked) {
                LockScreen(platform)
            } else {
                Product(application, platform)
            }
        }
    }
}

@Composable
private fun Product(
    app: PortfolioApplication,
    host: PlatformActions,
) {
    var page by rememberSaveable(
        stateSaver =
            Saver<Page, String>(save = {
                it.name
            }, restore = { Page.valueOf(it) }),
    ) { mutableStateOf(Page.OVERVIEW) }
    val overviewScroll = rememberLazyListState()
    val activityScroll = rememberLazyListState()
    val accountScroll = rememberLazyListState()
    val reportScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    var snapshot by remember(app) { mutableStateOf<PortfolioSnapshot?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var kind by remember { mutableStateOf<ActivityKind?>(null) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selectedAccount by remember(app) { mutableStateOf<AccountId?>(null) }
    var selectedPortfolio by remember(app) { mutableStateOf<PortfolioId?>(null) }
    var sourceDraftEndpoint by remember(app) { mutableStateOf<String?>(null) }
    var sourceDraftConsent by remember(app) { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedActivity by remember { mutableStateOf<EffectiveActivity?>(null) }
    var selectedAsset by remember { mutableStateOf<AssetValuation?>(null) }
    var chart by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var period by rememberSaveable { mutableIntStateOf(30) }
    val hostRevision by host.dataRevision.collectAsState()
    val syncStatus by host.syncStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun execute(
        success: String = "Saved on this device",
        action: () -> Unit,
    ) {
        scope.launch {
            busy = true
            try {
                withContext(host.dispatcher) { action() }
                revision++
                message = success
            } catch (_: Exception) {
                message = "Could not save. Check your input and available storage, then try again."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(app, page, revision, hostRevision, search, kind, offset, selectedAccount, selectedPortfolio, period) {
        try {
            val data =
                withContext(host.dispatcher) {
                    app.reload()
                    val result =
                        app.snapshot(
                            if (page ==
                                Page.ACTIVITY
                            ) {
                                ActivityFilter(
                                    search = search,
                                    account = selectedAccount,
                                    kind = kind,
                                    offset = offset,
                                    portfolio = selectedPortfolio,
                                )
                            } else {
                                ActivityFilter(portfolio = selectedPortfolio)
                            },
                        )
                    val end = Clock.System.now().toEpochMilliseconds()
                    val points = app.historySeries((0..12).map { end - (12L - it) * period * 86_400_000L / 12 }, selectedPortfolio)
                    result to points
                }
            snapshot = data.first
            chart = data.second
        } catch (_: Exception) {
            message = "Your data could not be loaded. Check storage and reopen the app."
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxWidth < 700.dp
        val wide = maxWidth >= 1120.dp
        Row(Modifier.fillMaxSize()) {
            if (!compact) {
                Sidebar(page, {
                    page = it
                    offset = 0
                }, Modifier.width(if (wide) 216.dp else 176.dp))
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = Paper,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (compact) {
                        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                            Page.entries.forEach { item ->
                                NavigationBarItem(selected = page == item, onClick = {
                                    page = item
                                    offset = 0
                                }, icon = {
                                    Text(item.glyph, fontSize = 23.sp)
                                }, label = {
                                    Text(
                                        item.label,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                    )
                                }, colors = NavigationBarItemDefaults.colors(indicatorColor = Mint))
                            }
                        }
                    }
                },
            ) { insets ->
                val data = snapshot
                LazyColumn(
                    Modifier.fillMaxSize().padding(insets),
                    state =
                        when (page) {
                            Page.OVERVIEW -> overviewScroll
                            Page.ACTIVITY -> activityScroll
                            Page.ACCOUNTS -> accountScroll
                            Page.REPORTS -> reportScroll
                            Page.SETTINGS -> settingsScroll
                        },
                    contentPadding =
                        PaddingValues(
                            horizontal = if (compact) 20.dp else 32.dp,
                            vertical = if (compact) 20.dp else 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (compact) "POLION / YOUR SPACE" else "YOUR FINANCIAL SPACE",
                                    color = Teal,
                                    fontSize = 10.sp,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(page.label, fontSize = if (compact) 30.sp else 34.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            }
                            if (page == Page.OVERVIEW ||
                                page == Page.ACTIVITY
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        dialog = "sync"
                                    },
                                    enabled = !busy && syncStatus.phase != SyncPhase.SYNCING,
                                    colors =
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Mint,
                                        ),
                                ) { Text(if (syncStatus.phase == SyncPhase.SYNCING) "Syncing…" else "↻  Sync") }
                            }
                        }
                    }
                    item { SyncBanner(syncStatus, data?.sourceStatus, { dialog = "sync" }) }
                    if (data?.sampleData == true) item { StatusPill("EXAMPLE DATA · stored only on this device", false) }
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal) }
                    if (data == null) {
                        item { EmptyCard("Opening your portfolio", "Your local data stays on this device.") }
                    } else {
                        when (page) {
                            Page.OVERVIEW -> {
                                item { PortfolioSelector(data, selectedPortfolio) { selectedPortfolio = it } }
                                item { BalanceHero(data, chart, compact, period, { period = it }, { dialog = "price" }) }
                                item {
                                    if (wide) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                AssetsCard(data, {
                                                    selectedAsset =
                                                        it
                                                }, { dialog = "price" })
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                HealthCard(data, {
                                                    dialog =
                                                        "sync"
                                                })
                                                ; QuickActions({ dialog = "account" }, { dialog = "activity" })
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            AssetsCard(data, { selectedAsset = it }, {
                                                dialog =
                                                    "price"
                                            })
                                            ; HealthCard(data, { dialog = "sync" })
                                        }
                                    }
                                }
                                item { SectionTitle("Recent activity", "View all") { page = Page.ACTIVITY } }
                                if (data.activities.isEmpty()) {
                                    item {
                                        EmptyCard(
                                            "A clear view starts here",
                                            "Add a watch-only account, then sync its public history. You can also record an activity manually.",
                                            "Add an account",
                                        ) {
                                            dialog =
                                                "account"
                                        }
                                    }
                                } else {
                                    items(data.activities.take(5), key = { it.activity.id.value }) {
                                        ActivityRow(it, data, compact) {
                                            selectedActivity =
                                                it
                                        }
                                    }
                                }
                                item { PrivacyFootnote() }
                            }
                            Page.ACTIVITY -> {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        OutlinedTextField(
                                            search,
                                            {
                                                search = it
                                                offset = 0
                                            },
                                            Modifier.weight(
                                                1f,
                                            ),
                                            label = { Text("Search activity") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                        )
                                        Button(
                                            onClick = { dialog = "activity" },
                                            enabled = data.ownership.accounts.isNotEmpty(),
                                        ) { Text("+ Add") }
                                    }
                                }
                                item {
                                    FilterKinds(kind) {
                                        kind = it
                                        offset = 0
                                    }
                                }
                                item {
                                    AccountSelector(data, selectedAccount) {
                                        selectedAccount = it
                                        offset = 0
                                    }
                                }
                                item {
                                    Text(
                                        "${data.activityTotal} activities · confirmed and pending",
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (data.activities.isEmpty()) {
                                    item {
                                        EmptyCard(
                                            "No matching activity",
                                            "Try another filter or sync an account to fetch its history.",
                                        )
                                    }
                                } else {
                                    items(data.activities, key = { it.activity.id.value }) {
                                        ActivityRow(it, data, compact) {
                                            selectedActivity =
                                                it
                                        }
                                    }
                                }
                                item {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        OutlinedButton(
                                            onClick = { offset = (offset - 50).coerceAtLeast(0) },
                                            enabled = offset > 0,
                                        ) { Text("Previous") }
                                        Text(
                                            "${if (data.activityTotal == 0) 0 else offset + 1}–${minOf(offset + 50, data.activityTotal)}",
                                            color = Muted,
                                            modifier = Modifier.padding(top = 12.dp),
                                        )
                                        OutlinedButton(
                                            onClick = { offset += 50 },
                                            enabled = offset + 50 < data.activityTotal,
                                        ) { Text("Next") }
                                    }
                                }
                            }
                            Page.ACCOUNTS -> {
                                item { SectionTitle("Watch-only accounts", "+ Add") { dialog = "account" } }
                                if (data.ownership.accounts.isEmpty()) {
                                    item {
                                        EmptyCard(
                                            "Your keys stay yours",
                                            "Track a public Bitcoin address. Polion never needs your recovery phrase or private keys.",
                                            "Add your first account",
                                        ) {
                                            dialog =
                                                "account"
                                        }
                                    }
                                }
                                items(data.ownership.accounts, key = { it.id.value }) { account ->
                                    Panel {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CoinBadge(if (account.kind == AccountKind.GROUP) "▣" else "₿")
                                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                                Text(account.label, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    "${data.ownership.owners
                                                        .find { it.id == account.ownerId }
                                                        ?.label ?: "Owner"} · ${account.kind.name.lowercase()}",
                                                    color = Muted,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                        data.ownership.targets.filter { it.accountId == account.id }.forEach { target ->
                                            HorizontalDivider(color = Line)
                                            Text(target.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                            Text(target.identity.canonicalKey, color = Muted, fontSize = 12.sp)
                                            StatusPill(
                                                if (target.enabled) "Watching · ${target.identity.kind}" else "Paused",
                                                target.enabled,
                                            )
                                        }
                                        data.balances.quantities.filterKeys { it.account == account.id }.forEach { (key, units) ->
                                            Text(
                                                "${formatUnits(
                                                    units,
                                                    data.valuations
                                                        .find { it.asset == key.asset }
                                                        ?.metadata
                                                        ?.decimals ?: 8,
                                                )} ${symbol(key.asset, data)}",
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                                item { SectionTitle("Organize your space") }
                                item {
                                    WrapActions(
                                        listOf(
                                            "Add owner" to { dialog = "owner" },
                                            "Account group" to { dialog = "group" },
                                            "New portfolio" to { dialog = "portfolio" },
                                        ),
                                    )
                                }
                                items(data.ownership.portfolios) { portfolio ->
                                    Panel {
                                        Text(portfolio.label, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${portfolio.valuationCurrency} · ${if (portfolio.accountIds.isEmpty()) "All accounts" else "${portfolio.accountIds.size} accounts"}",
                                            color = Muted,
                                        )
                                    }
                                }
                            }
                            Page.REPORTS -> {
                                item { PortfolioSelector(data, selectedPortfolio) { selectedPortfolio = it } }
                                item { BalanceHero(data, chart, compact, period, { period = it }, { dialog = "price" }) }
                                item { AnalyticsPanel(app, host, selectedPortfolio, revision + hostRevision.toInt()) }
                                item { AccountingPanel(app, host, selectedPortfolio, revision + hostRevision.toInt()) { revision++ } }
                                item {
                                    Panel {
                                        Text("Understand your cost basis", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text(
                                            "FIFO follows the oldest available lots. An incoming transfer does not establish a purchase price. Unknown purchase costs remain unknown, and pending activity is excluded from confirmed reports.",
                                            color = Muted,
                                            lineHeight = 22.sp,
                                        )
                                        Text(
                                            "Values use saved price observations at each point in time. Changes in the chart may reflect deposits and withdrawals, not investment performance.",
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                        OutlinedButton(onClick = {
                                            execute("Export ready") {
                                                val csv = app.exportCsv()
                                                host.saveFile("polion-activities.csv", csv.encodeToByteArray()) {
                                                    message =
                                                        it
                                                }
                                            }
                                        }) { Text("↓  Export exact activity CSV") }
                                    }
                                }
                                item { SectionTitle("Reconciliation", "Review") { dialog = "reconcile" } }
                                item {
                                    Text(
                                        "${data.duplicateConflicts.size} unresolved duplicate conflicts. Transfer suggestions always require your review.",
                                        color = Muted,
                                    )
                                }
                            }
                            Page.SETTINGS -> {
                                item {
                                    Panel {
                                        Text("Make it yours", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text("Display currency", color = Muted, fontSize = 12.sp)
                                        WrapActions(
                                            listOf(
                                                "USD" to { execute { app.setCurrency(AssetId("USD")) } },
                                                "EUR" to { execute { app.setCurrency(AssetId("EUR")) } },
                                                "GBP" to { execute { app.setCurrency(AssetId("GBP")) } },
                                            ),
                                        )
                                        Text(
                                            "Current: ${data.currency.value}. Add a manual quote for each asset and currency you use.",
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                        TextButton(onClick = { dialog = "price" }) { Text("Manage manual prices →") }
                                    }
                                }
                                item {
                                    Panel {
                                        Text("Privacy & sync", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text(
                                            "Public indexers can associate queried addresses with your IP address. Choose your own endpoint or stay offline.",
                                            color = Muted,
                                            lineHeight = 22.sp,
                                        )
                                        OutlinedButton(onClick = { dialog = "sync" }) { Text("Source & background updates") }
                                        OutlinedButton(onClick = { dialog = "lock" }) { Text("Set app lock") }
                                        TextButton(onClick = host::lock, enabled = host.hasLock) { Text("Lock now") }
                                        if (host.hasLock) {
                                            TextButton(onClick = {
                                                host.removeLockPassword {
                                                    message = it
                                                    revision++
                                                }
                                            }) { Text("Remove privacy lock") }
                                        }
                                        Text(
                                            "App lock hides the screen. The local database is stored in plaintext in your app data directory; use device or disk encryption for protection at rest.",
                                            color = Muted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                item {
                                    Panel {
                                        Text("Your data, in your hands", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text(
                                            "Create an encrypted backup before replacing or removing local data. Your password cannot be recovered.",
                                            color = Muted,
                                            lineHeight = 22.sp,
                                        )
                                        WrapActions(
                                            listOf(
                                                "Encrypted backup" to { dialog = "backup" },
                                                "Restore backup" to { dialog = "restore" },
                                                "Activity CSV" to
                                                    {
                                                        execute("Export ready") {
                                                            host.saveFile("polion-activities.csv", app.exportCsv().encodeToByteArray()) {
                                                                message =
                                                                    it
                                                            }
                                                        }
                                                    },
                                            ),
                                        )
                                        TextButton(
                                            onClick = { dialog = "wipe" },
                                        ) { Text("Erase local portfolio data", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                                item {
                                    Panel {
                                        Text("Classification rules", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text(
                                            "Apply a repeatable classification to matching activity. Per-activity corrections take precedence.",
                                            color = Muted,
                                        )
                                        OutlinedButton(onClick = { dialog = "rule" }) { Text("Add a rule") }
                                    }
                                }
                                if (data.ownership.owners.isEmpty()) {
                                    item {
                                        TextButton(onClick = {
                                            execute("Example portfolio loaded. This is sample data.") { app.seedDemo() }
                                        }) { Text("Explore with sample data") }
                                    }
                                }
                                item { PrivacyFootnote() }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
        if (dialog != null &&
            snapshot != null
        ) {
            WorkflowDialog(
                dialog!!,
                snapshot!!.copy(
                    sourceEndpoint = sourceDraftEndpoint ?: snapshot!!.sourceEndpoint,
                    sourceConsent = sourceDraftConsent ?: snapshot!!.sourceConsent,
                ),
                app,
                host,
                { dialog = null },
                { success, action -> execute(success, action) },
                {
                    message = it
                    revision++
                },
                { endpoint, allowed ->
                    sourceDraftEndpoint = endpoint
                    sourceDraftConsent = allowed
                },
            )
        }
        selectedActivity?.let { item ->
            ActivityDialog(item, snapshot!!, { selectedActivity = null }) { overlay ->
                execute("Correction saved; original evidence preserved") { app.saveOverlay(overlay) }
                selectedActivity =
                    null
            }
        }
        selectedAsset?.let { asset ->
            AssetDialog(asset, snapshot!!, { selectedAsset = null }, {
                selectedAsset = null
                dialog = "price"
            })
        }
    }
}

@Composable
private fun Sidebar(
    selected: Page,
    onSelect: (Page) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxHeight().background(Color.White).padding(horizontal = 16.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Ink), contentAlignment = Alignment.Center) {
                Text("p", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 25.sp)
            }
            Text(
                "polion",
                fontSize = 26.sp,
                letterSpacing = (-1).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            "WORKSPACE",
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            color = Muted,
            modifier = Modifier.padding(start = 14.dp, top = 34.dp, bottom = 12.dp),
        )
        Page.entries.forEach { page ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected ==
                            page
                        ) {
                            Mint
                        } else {
                            Color.Transparent
                        },
                    ).clickable { onSelect(page) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(page.glyph, fontSize = 20.sp, color = if (selected == page) Teal else Muted)
                Text(
                    page.label,
                    fontSize = 13.sp,
                    color =
                        if (selected ==
                            page
                        ) {
                            Ink
                        } else {
                            Muted
                        },
                    fontWeight =
                        if (selected ==
                            page
                        ) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Paper)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("◉  Local first", color = Teal, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Your portfolio.\nYour device. Your control.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun BalanceHero(
    data: PortfolioSnapshot,
    points: List<HistoryPoint>,
    compact: Boolean,
    period: Int,
    onPeriod: (Int) -> Unit,
    onPrice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Corners)
            .background(Ink)
            .padding(if (compact) 22.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("PORTFOLIO VALUE", color = Color(0xFFB6CFC2), fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium)
            Text(data.currency.value, color = Color(0xFFB6CFC2), fontSize = 12.sp)
        }
        val known = data.valuations.filter { it.value != null }
        val total = known.fold(ExactDecimal.parse("0")) { sum, it -> sum + it.value!! }
        Text(
            if (known.isEmpty()) "—" else money(total, data.currency),
            color = Color.White,
            fontSize = if (compact) 34.sp else 44.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1).sp,
            maxLines = 2,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).background(Color(0xFFA9D6B6), CircleShape))
            Text(
                if (data.coverage.name ==
                    "COMPLETE"
                ) {
                    "Confirmed history"
                } else {
                    "Partial view · history coverage unverified"
                },
                color = Color(0xFFC5DCD0),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
        }
        ValueChart(points, Modifier.fillMaxWidth().height(if (compact) 112.dp else 150.dp), dark = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7 to "1W", 30 to "1M", 90 to "3M", 365 to "1Y").forEach { (days, label) ->
                    Text(
                        label,
                        color =
                            if (period ==
                                days
                            ) {
                                Ink
                            } else {
                                Color(0xFFB6CFC2)
                            },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (period ==
                                        days
                                    ) {
                                        Color(0xFFD6E9DD)
                                    } else {
                                        Color.Transparent
                                    },
                                ).clickable { onPeriod(days) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (!compact) Text("Historical value", color = Color(0xFFB6CFC2), fontSize = 11.sp)
        }
        val missing = data.valuations.count { it.value == null }
        if (missing >
            0
        ) {
            Text(
                "$missing unpriced asset${if (missing == 1) "" else "s"} excluded · Add a price →",
                color = Color(0xFFE9D3A6),
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onPrice).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ValueChart(
    points: List<HistoryPoint>,
    modifier: Modifier,
    dark: Boolean,
) {
    // Conversion to Float is exclusively for drawing coordinates; authoritative values remain exact.
    val values =
        points.map { point ->
            point.valuations
                .takeIf { it.isNotEmpty() && it.all { v -> v.value != null } }
                ?.fold(ExactDecimal.parse("0")) { sum, v ->
                    sum +
                        v.value!!
                }?.toString()
                ?.toDoubleOrNull()
        }
    if (values.none { it != null }) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Add historical prices to see your value over time", color = if (dark) Color(0xFFAAC6B7) else Muted, fontSize = 12.sp)
        }
    } else {
        Canvas(modifier) {
            val finite = values.filterNotNull().filter { it.isFinite() }
            if (finite.isEmpty()) return@Canvas
            val min = finite.min()
            val max = finite.max()
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val stroke = if (dark) Color(0xFFA9D6B6) else Teal
            repeat(3) { row ->
                val y = size.height * row / 2
                drawLine(stroke.copy(alpha = 0.13f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val path = Path()
            var pen = false
            values.forEachIndexed { index, v ->
                if (v == null || !v.isFinite()) {
                    pen = false
                } else {
                    val x = size.width * index / (values.size - 1).coerceAtLeast(1)
                    val y = (size.height * (0.9 - 0.8 * ((v - min) / range))).toFloat()
                    if (pen) path.lineTo(x, y) else path.moveTo(x, y)
                    pen = true
                    drawCircle(stroke, 2.5f, Offset(x, y))
                }
            }
            drawPath(path, stroke, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

@Composable
private fun AssetsCard(
    data: PortfolioSnapshot,
    onAsset: (AssetValuation) -> Unit,
    onPrice: () -> Unit,
) {
    Panel {
        SectionTitle("Your assets", "Prices") { onPrice() }
        if (data.valuations.isEmpty()) {
            Text(
                "Confirmed assets will appear after you add activity or sync an account.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 21.sp,
            )
        }
        data.valuations.forEach { asset ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onAsset(asset)
                    }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoinBadge(if (asset.metadata?.symbol == "BTC") "₿" else asset.metadata?.symbol?.take(1) ?: "?")
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        asset.metadata?.name ?: asset.asset.value,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatUnits(asset.amount.atomicUnits, asset.metadata?.decimals ?: 8)} ${asset.metadata?.symbol ?: "units"}",
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(asset.value?.let { money(it, data.currency) } ?: "Unpriced", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        if (asset.price?.manual ==
                            true
                        ) {
                            "Manual quote"
                        } else {
                            asset.priceStatus.name
                                .lowercase()
                                .replaceFirstChar { it.uppercase() }
                        },
                        fontSize = 10.sp,
                        color =
                            if (asset.priceStatus ==
                                PriceStatus.FRESH
                            ) {
                                Teal
                            } else {
                                Orange
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthCard(
    data: PortfolioSnapshot,
    onSync: () -> Unit,
) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Data health", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("◎", color = Teal, fontSize = 22.sp)
        }
        Text("Clarity before confidence.", color = Muted, fontSize = 13.sp)
        data.sourceStatus.lastSuccessfulRefresh?.let { Text("Last saved sync: ${date(it)} UTC", fontSize = 11.sp, color = Muted) }
        data.sourceStatus.lastError?.let { Text("Sync incomplete · saved data retained", color = Orange, fontSize = 12.sp) }
        HorizontalDivider(color = Line)
        HealthLine("History", if (data.coverage.name == "COMPLETE") "Complete" else "Unverified", data.coverage.name == "COMPLETE")
        HealthLine("Pending activity", data.pendingCount.toString(), false)
        HealthLine("Unpriced assets", data.valuations.count { it.value == null }.toString(), data.valuations.none { it.value == null })
        TextButton(onClick = onSync, contentPadding = PaddingValues(0.dp)) { Text("Review sources & sync →", fontSize = 12.sp) }
    }
}

@Composable private fun HealthLine(
    label: String,
    value: String,
    positive: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = if (positive) Teal else Orange, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun QuickActions(
    account: () -> Unit,
    activity: () -> Unit,
) {
    Panel {
        Text("Keep things in order", fontWeight = FontWeight.SemiBold)
        TextButton(onClick = account) { Text("+  Track an account") }
        TextButton(onClick = activity) { Text("+  Record an activity") }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Corners)
            .background(Color.White)
            .border(1.dp, Line, Corners)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable private fun CoinBadge(text: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(
            if (text ==
                "₿"
            ) {
                Color(0xFFFFF0DE)
            } else {
                Mint
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color =
                if (text ==
                    "₿"
                ) {
                    Orange
                } else {
                    Teal
                },
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable private fun SectionTitle(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action !=
            null
        ) {
            TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
        }
    }
}

@Composable private fun StatusPill(
    text: String,
    positive: Boolean,
) {
    Text(
        text,
        color = if (positive) Teal else Color(0xFF936126),
        fontSize = 10.sp,
        modifier =
            Modifier
                .clip(
                    RoundedCornerShape(6.dp),
                ).background(if (positive) Mint else Color(0xFFFFF0D8))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable private fun PrivacyFootnote() {
    Text(
        "◉  Watch-only. Local storage. No cloud account.\nPublic source requests reveal addresses to your chosen indexer.",
        color = Muted,
        fontSize = 11.sp,
        lineHeight = 18.sp,
    )
}

@Composable private fun EmptyCard(
    title: String,
    detail: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Panel {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(detail, color = Muted, lineHeight = 22.sp)
        if (action !=
            null
        ) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapActions(actions: List<Pair<String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { (label, action) ->
            OutlinedButton(onClick = action) { Text(label, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ActivityRow(
    item: EffectiveActivity,
    data: PortfolioSnapshot,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val a = item.activity
    Row(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(16.dp),
            ).background(Color.White)
            .clickable(onClick = onClick)
            .padding(if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Paper), contentAlignment = Alignment.Center) {
            Text(
                if (a.movements
                        .first()
                        .amount.atomicUnits.sign >=
                    0
                ) {
                    "↙"
                } else {
                    "↗"
                },
                fontSize = 21.sp,
                color = Teal,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.label ?: a.kind.name.lowercase().replaceFirstChar {
                    it.uppercase()
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                date(a.occurredAtEpochMillis) + if (!compact) " · ${a.sources.size} source records" else "",
                color = Muted,
                fontSize = 10.sp,
            )
            if (a.status != ActivityStatus.CONFIRMED) {
                StatusPill(
                    a.status.name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                    false,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = if (compact) 125.dp else 220.dp)) {
            a.movements.groupBy { it.amount.assetId }.forEach { (asset, movements) ->
                val quantity = movements.fold(ExactInteger.ZERO) { sum, m -> sum + m.amount.atomicUnits }
                Text(
                    "${if (quantity.sign > 0) "+" else ""}${formatUnits(
                        quantity,
                        data.valuations
                            .find { it.asset == asset }
                            ?.metadata
                            ?.decimals ?: 8,
                    )} ${symbol(asset, data)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color =
                        if (quantity.sign >
                            0
                        ) {
                            Teal
                        } else {
                            Ink
                        },
                    maxLines = 2,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterKinds(
    selected: ActivityKind?,
    onSelect: (ActivityKind?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected == null,
            { onSelect(null) },
            label = { Text("All") },
        )
        ; ActivityKind.entries.forEach { kind ->
            FilterChip(selected == kind, {
                onSelect(kind)
            }, label = { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) })
        }
    }
}

@Composable private fun AccountSelector(
    data: PortfolioSnapshot,
    selected: AccountId?,
    onSelect: (AccountId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = {
            expanded =
                true
        }) {
            Text(
                data.ownership.accounts
                    .find { it.id == selected }
                    ?.label ?: "All accounts",
            )
            Text(" ▾")
        }
        ; DropdownMenu(expanded, {
            expanded =
                false
        }) {
            DropdownMenuItem({ Text("All accounts") }, {
                onSelect(null)
                expanded = false
            })
            data.ownership.accounts.forEach { a ->
                DropdownMenuItem({ Text(a.label) }, {
                    onSelect(a.id)
                    expanded =
                        false
                })
            }
        }
    }
}

@Composable private fun PortfolioSelector(
    data: PortfolioSnapshot,
    selected: PortfolioId?,
    onSelect: (PortfolioId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = {
            expanded =
                true
        }, contentPadding = PaddingValues(0.dp)) {
            Text(
                data.ownership.portfolios
                    .find { it.id == selected }
                    ?.label ?: "All portfolios",
                color = Ink,
                fontWeight = FontWeight.Medium,
            )
            Text("  ▾")
        }
        ; DropdownMenu(expanded, {
            expanded =
                false
        }) {
            DropdownMenuItem({ Text("All portfolios") }, {
                onSelect(null)
                expanded = false
            })
            data.ownership.portfolios.forEach { p ->
                DropdownMenuItem({ Text(p.label) }, {
                    onSelect(p.id)
                    expanded =
                        false
                })
            }
        }
    }
}

@Composable private fun Allocation(data: PortfolioSnapshot) {
    val values =
        data.valuations.mapNotNull { v ->
            v.value
                ?.toString()
                ?.toDoubleOrNull()
                ?.takeIf {
                    it >
                        0 &&
                        it.isFinite()
                }?.let { v to it }
        }
    ; val total = values.sumOf { it.second }
    values.forEach { (asset, value) ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    asset.metadata?.name ?: asset.asset.value,
                    fontSize = 12.sp,
                )
                ; Text("${(value / total * 100).toInt()}%", color = Muted, fontSize = 12.sp)
            }
            ; LinearProgressIndicator(progress = {
                (
                    value /
                        total
                ).toFloat()
            }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Teal, trackColor = Mint)
        }
    }
}

private fun symbol(
    asset: AssetId,
    data: PortfolioSnapshot,
) = data.valuations
    .find { it.asset == asset }
    ?.metadata
    ?.symbol ?: asset.value.substringAfterLast(':').uppercase()

private fun formatUnits(
    units: ExactInteger,
    decimals: Int,
): String =
    ExactDecimal(units, decimals).toString().let {
        if ('.' in
            it
        ) {
            it.trimEnd('0').trimEnd('.')
        } else {
            it
        }
    }

private fun money(
    value: ExactDecimal,
    currency: AssetId,
): String {
    val prefix =
        when (currency.value) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            else -> "${currency.value} "
        }
    val rounded = value.rescale(2, Rounding.HALF_EVEN).toString()
    val parts = rounded.removePrefix("-").split('.')
    val grouped =
        parts
            .first()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    return (if (rounded.startsWith('-')) "-" else "") + prefix + grouped + "." + parts.last()
}

private fun date(millis: Long) = Instant.fromEpochMilliseconds(millis).toString().take(10)

private fun newId(prefix: String) = "$prefix-${Clock.System.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(1_000_000)}"

@Composable
private fun SyncBanner(
    status: SyncStatus,
    saved: SourceStatus?,
    onDetails: () -> Unit,
) {
    val running = status.phase == SyncPhase.SYNCING
    val title =
        when (status.phase) {
            SyncPhase.SYNCING -> "Syncing…"
            SyncPhase.SUCCESS -> "Sync complete"
            SyncPhase.ERROR -> "Sync needs attention"
            SyncPhase.IDLE ->
                if (saved?.interrupted ==
                    true
                ) {
                    "Sync interrupted"
                } else if (saved?.lastSuccessfulRefresh != null) {
                    "Saved history available"
                } else {
                    "Not synced yet"
                }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (status.phase ==
                    SyncPhase.ERROR
                ) {
                    Color(0xFFFFEEDB)
                } else {
                    Mint
                },
            ).clickable(onClick = onDetails)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
            Text("Details →", fontSize = 11.sp, color = Teal)
        }
        Text(
            if (status.phase !=
                SyncPhase.IDLE
            ) {
                status.message
            } else {
                saved?.takeIf { it.interrupted }?.let { "The previous sync stopped before completion. Tap Sync to retry safely." }
                    ?: saved?.lastSuccessfulRefresh?.let {
                        "Last successful sync: ${Instant.fromEpochMilliseconds(
                            it,
                        ).toString().replace('T', ' ').take(19)} UTC"
                    }
                    ?: "Add an address and allow a provider to fetch public history."
            },
            fontSize = 12.sp,
            color = Muted,
        )
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal, trackColor = Color.White)
            Text("History is being fetched. Saved balances update after the full scan is checked.", fontSize = 11.sp, color = Muted)
        }
        if (!running &&
            saved?.lastError != null
        ) {
            Text("Previous sync incomplete. Saved history has been retained.", fontSize = 11.sp, color = Orange)
        }
    }
}
