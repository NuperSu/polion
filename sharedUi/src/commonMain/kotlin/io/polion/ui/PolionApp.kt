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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.application.ActivityFilter
import io.polion.core.application.AssetAllocation
import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetSort
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
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.onboarding.OnboardingState
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.reconcile.EffectiveActivity
import io.polion.core.sources.SourceWorkflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun PolionApp(
    application: PortfolioApplication,
    platform: PlatformActions,
    locked: Boolean = false,
) {
    var appearance by remember(application) { mutableStateOf(AppearanceTheme.fromKey(application.appearanceTheme())) }
    val preferenceRevision by platform.dataRevision.collectAsState()
    LaunchedEffect(application, preferenceRevision) {
        appearance = AppearanceTheme.fromKey(application.appearanceTheme())
    }
    PolionTheme(appearance) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            // The product stays composed under the lock, so in-progress forms and selections survive a lock cycle.
            OnboardingProduct(application, platform, appearance) { selected -> appearance = selected }
            if (locked) {
                Surface(Modifier.fillMaxSize(), color = Paper) { LockScreen(platform) }
            }
        }
    }
}

@Composable
private fun OnboardingProduct(
    app: PortfolioApplication,
    host: PlatformActions,
    appearance: AppearanceTheme,
    onAppearance: (AppearanceTheme) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember(app) { mutableStateOf<OnboardingState?>(null) }
    var loaded by remember(app) { mutableStateOf(false) }
    var busy by remember(app) { mutableStateOf(false) }
    var error by remember(app) { mutableStateOf<String?>(null) }
    var workflow by remember(app) { mutableStateOf<SourceWorkflow?>(null) }
    var localRevision by remember(app) { mutableStateOf(0) }
    val revision by host.dataRevision.collectAsState()
    LaunchedEffect(app, revision, localRevision) {
        try {
            val saved = withContext(host.dispatcher) { app.onboardingState() }
            state = saved
            loaded = true
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Setup could not be opened. Check available storage and reopen Polion."
        }
    }

    fun update(
        action: OnboardingAction,
        afterSave: () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val saved = withContext(host.dispatcher) { app.updateOnboarding(action) }
                state = saved
                afterSave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Setup could not be saved. Check available storage and try again."
            } finally {
                busy = false
            }
        }
    }
    val current = state
    if (!loaded) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(error ?: "Opening your portfolio")
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else if (current != null && !current.completed && !current.skipped) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            OnboardingScreen(current, busy, error, { update(it) }) { selected ->
                update(OnboardingAction.Complete) { workflow = selected }
            }
        }
    } else {
        Product(
            app,
            host,
            appearance,
            onAppearance,
            onSetup = { restart -> update(if (restart) OnboardingAction.Restart else OnboardingAction.Resume) },
            setupError = error,
            initialWorkflow = workflow,
            onWorkflowOpened = { workflow = null },
            onLocalDataChanged = { localRevision++ },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Product(
    app: PortfolioApplication,
    host: PlatformActions,
    appearance: AppearanceTheme,
    onAppearance: (AppearanceTheme) -> Unit,
    onSetup: (Boolean) -> Unit,
    setupError: String?,
    initialWorkflow: SourceWorkflow?,
    onWorkflowOpened: () -> Unit,
    onLocalDataChanged: () -> Unit,
) {
    val presenter = rememberProductPresenter(app, host)
    var manualAsset by remember { mutableStateOf<AssetId?>(null) }
    var advancedMode by rememberSaveable { mutableStateOf(false) }
    var modelConnect by rememberSaveable { mutableStateOf(false) }
    var modelHistory by rememberSaveable { mutableStateOf(false) }
    val sourceFlowState = rememberSaveableStateHolder()
    var modelAdd by rememberSaveable { mutableStateOf(false) }
    var modelEditingId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelEditingDraft by remember { mutableStateOf<io.polion.core.application.TransactionDraft?>(null) }
    LaunchedEffect(modelEditingId) {
        modelEditingDraft =
            modelEditingId?.let { id ->
                withContext(host.dispatcher) {
                    app.transactionDraft(
                        io.polion.core.model
                            .ActivityId(id),
                    )
                }
            }
    }
    var modelManual by rememberSaveable { mutableStateOf(false) }
    val modelDraftState = rememberSaveableStateHolder()
    // Drafts are scoped to their starting context, so an entry opened from one coin never reuses another coin's draft.
    val modelDraftScope = modelEditingId ?: "manual-draft:${manualAsset?.value.orEmpty()}"
    val modelWorkspaceState = rememberSaveableStateHolder()
    var modelReturnPage by rememberSaveable { mutableStateOf(Page.OVERVIEW.name) }
    var advancedOrigin by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRefreshDetails by remember { mutableStateOf(false) }
    var preferences by remember(host) { mutableStateOf(RefreshPreferences()) }
    val preferenceRevision by host.dataRevision.collectAsState()
    LaunchedEffect(app, host, preferenceRevision) {
        preferences = withContext(host.dispatcher) { host.refreshPreferences() }
    }
    var refreshDetails by remember { mutableStateOf<String?>(null) }
    var assetPreference by remember(app) { mutableStateOf(app.assetListPreference()) }
    var page by presenter.field({ it.page }) { presenter.dispatch(ProductEvent.Navigate(it)) }
    val snapshot = presenter.state.snapshot
    val snapshotFilter = presenter.state.snapshotFilter
    var revision by presenter.field({ it.revision }) { presenter.update { copy(revision = it) } }
    val dashboardRevision by host.dataRevision.collectAsState()
    LaunchedEffect(app, dashboardRevision, revision) {
        assetPreference = withContext(host.dispatcher) { app.assetListPreference() }
    }
    LaunchedEffect(revision) {
        if (revision != 0) onLocalDataChanged()
    }
    var search by presenter.field({ it.search }) { presenter.dispatch(ProductEvent.Search(it)) }
    var kind by presenter.field({ it.kind }) { presenter.dispatch(ProductEvent.Kind(it)) }
    var activityStatus by presenter.field({ it.activityStatus }) { presenter.dispatch(ProductEvent.Status(it)) }
    var activityNetwork by presenter.field({ it.activityNetwork }) { presenter.dispatch(ProductEvent.Network(it)) }
    var offset by presenter.field({ it.offset }) { presenter.dispatch(ProductEvent.Offset(it)) }
    var selectedAccount by presenter.field({ it.selectedAccount }) { presenter.dispatch(ProductEvent.Account(it)) }
    var selectedPortfolio by presenter.field({ it.selectedPortfolio }) { presenter.dispatch(ProductEvent.Portfolio(it)) }
    var sourceDraftEndpoint by presenter.field({ it.sourceDraftEndpoint }) { presenter.update { copy(sourceDraftEndpoint = it) } }
    var sourceDraftConsent by presenter.field({ it.sourceDraftConsent }) { presenter.update { copy(sourceDraftConsent = it) } }
    val busy = presenter.state.busy
    var selectedActivity by presenter.field({ it.selectedActivity }) { presenter.update { copy(selectedActivity = it) } }
    var selectedManualDraft by remember { mutableStateOf<io.polion.core.application.TransactionDraft?>(null) }
    var selectedManualCanEdit by remember { mutableStateOf(false) }
    LaunchedEffect(selectedActivity?.activity?.id) {
        selectedManualDraft = null
        selectedManualCanEdit = false
        selectedActivity?.takeIf { it.original.manual }?.let { item ->
            val (draft, overlay) = withContext(host.dispatcher) { app.transactionDraft(item.activity.id) to app.overlay(item.activity.id) }
            selectedManualDraft = draft
            selectedManualCanEdit = draft != null && (overlay == null || overlay.amounts.isEmpty() && overlay.kind == null)
        }
    }
    var addressWallet by presenter.field({ it.addressWallet }) { presenter.update { copy(addressWallet = it) } }
    var sourceScanTarget by remember { mutableStateOf<io.polion.core.ownership.TrackingTarget?>(null) }
    var removeAccount by presenter.field({ it.removeAccount }) { presenter.update { copy(removeAccount = it) } }
    var selectedAsset by presenter.field({ state ->
        state.snapshot?.valuations?.find { it.asset == state.detailAsset }
            ?: state.snapshot
                ?.marketCoins
                ?.find { it.asset == state.detailAsset }
                ?.detailValuation()
            ?: if (state.snapshot == null || state.snapshotFilter.portfolio != state.requestedFilter().portfolio) {
                state.selectedAsset
            } else {
                null
            }
    }) { asset -> presenter.update { copy(selectedAsset = asset, detailAsset = asset?.asset) } }
    val chart = if (presenter.state.chartCurrency == snapshot?.currency) presenter.state.chart else emptyList()
    var period by presenter.field({ it.period }) { presenter.dispatch(ProductEvent.Period(it)) }
    var dialog by presenter.field({ it.dialog?.key }) { presenter.dispatch(ProductEvent.Dialog(ProductDialog.fromKey(it))) }
    var message by presenter.field<String?>({ null }) { it?.let(presenter::notify) }
    LaunchedEffect(initialWorkflow) {
        initialWorkflow?.let {
            dialog = it.productDialog().key
            onWorkflowOpened()
        }
    }
    val homeScroll = rememberLazyListState()
    val moreScroll = rememberLazyListState()
    val overviewScroll = rememberLazyListState()
    val activityScroll = rememberLazyListState()
    val accountScroll = rememberLazyListState()
    val reportScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val hostRevision by host.dataRevision.collectAsState()
    val syncStatus by host.syncStatus.collectAsState()
    val pricePhase by host.priceRefreshStatus.collectAsState()
    var notifiedPriceResult by remember { mutableStateOf<String?>(null) }
    var priceRequested by remember { mutableStateOf(false) }
    var observedPriceWork by remember { mutableStateOf(false) }
    LaunchedEffect(pricePhase) {
        if (pricePhase != PriceRefreshPhase.IDLE) observedPriceWork = true
        if (pricePhase == PriceRefreshPhase.IDLE && observedPriceWork) {
            observedPriceWork = false
            priceRequested = false
            val result = withContext(host.dispatcher) { app.automaticPriceStatus() }
            refreshDetails = result ?: "Price refresh finished. Saved values remain available."
            if (result != notifiedPriceResult || result == null) {
                presenter.notify(
                    if (result?.startsWith("Last refresh succeeded") ==
                        true
                    ) {
                        "Prices refreshed"
                    } else {
                        "Price refresh finished · see refresh details"
                    },
                )
                notifiedPriceResult = result
            }
        }
    }

    LaunchedEffect(preferenceRevision) {
        if (priceRequested && pricePhase != PriceRefreshPhase.IDLE) {
            val result = withContext(host.dispatcher) { app.automaticPriceStatus() }
            if (result != null &&
                result != notifiedPriceResult &&
                (result.startsWith("Some price requests failed") || result.contains("rate limiting"))
            ) {
                refreshDetails = result
                notifiedPriceResult = result
                presenter.notify("Price refresh delayed · saved prices retained. See refresh details.")
            }
        }
    }

    fun refreshPrices() {
        if (priceRequested || pricePhase != PriceRefreshPhase.IDLE) return
        val allowedCurrencies = app.automaticPriceCurrencies().map { it.value }.toSet()
        val missingCurrencyConsent =
            snapshot?.ownership?.portfolios.orEmpty().any {
                it.valuationCurrency in setOf("USD", "EUR", "GBP") && it.valuationCurrency !in allowedCurrencies
            }
        if (!app.automaticPriceConsent() || missingCurrencyConsent) {
            if (missingCurrencyConsent) presenter.notify("Allow other portfolio currencies in price setup to refresh all portfolios.")
            selectedAsset = null
            dialog = "price"
            return
        }
        priceRequested = true
        notifiedPriceResult = null
        host.refreshAutomaticPrices { result ->
            refreshDetails = result
            if (host.priceRefreshStatus.value == PriceRefreshPhase.IDLE && !result.startsWith("Price refresh queued")) {
                priceRequested = false
                presenter.notify(result)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val snackbarInset =
        with(LocalDensity.current) { snackbarHeight.toDp() } +
            if (snackbarHeight > 0) {
                if (page == Page.OVERVIEW) 88.dp else 16.dp
            } else {
                0.dp
            }

    fun execute(
        success: String = "Saved on this device",
        action: () -> Unit,
    ) = presenter.execute(success, action)

    fun saveAssetPreference(preference: AssetListPreference) =
        execute("Asset display saved") {
            app.setAssetListPreference(preference)
            assetPreference = preference
        }
    LaunchedEffect(presenter, snackbar) {
        presenter.messages.collect { snackbar.showSnackbar(it) }
    }
    if (dialog == "add") {
        SupportedSourceDialog(
            onSelect = { choice ->
                manualAsset = null
                dialog = choice.workflow.productDialog().key
            },
            onDismiss = { dialog = null },
        )
    }

    fun returnFromAdvanced() {
        advancedMode = false
        if (advancedOrigin.size >= 10) {
            val saved = advancedOrigin
            presenter.update {
                val restored =
                    copy(
                        page = Page.valueOf(saved[0]),
                        detailAsset = saved[1].takeIf(String::isNotEmpty)?.let(::AssetId),
                        selectedAsset = null,
                        selectedPortfolio =
                            if (saved.size >
                                10
                            ) {
                                saved[10].takeIf(String::isNotEmpty)?.let(::PortfolioId)
                            } else {
                                selectedPortfolio
                            },
                    )
                if (saved[9] != "true") {
                    restored
                } else {
                    restored.copy(
                        search = saved[2],
                        kind = saved[3].takeIf(String::isNotEmpty)?.let(ActivityKind::valueOf),
                        activityStatus = saved[4].takeIf(String::isNotEmpty)?.let(io.polion.core.ledger.ActivityStatus::valueOf),
                        activityNetwork = saved[5].ifEmpty { null },
                        selectedAccount = saved[6].takeIf(String::isNotEmpty)?.let(::AccountId),
                        offset = saved[7].toInt(),
                        activityAsset = saved[8].takeIf(String::isNotEmpty)?.let(::AssetId),
                    )
                }
            }
            advancedOrigin = emptyList()
        } else {
            page = Page.OVERVIEW
        }
    }
    val modelMode = !advancedMode && page in setOf(Page.HOME, Page.OVERVIEW, Page.REPORTS, Page.ACCOUNTS, Page.SETTINGS)
    val canGoBack = advancedMode || selectedAsset != null || page == Page.ACCOUNTS || page == Page.SETTINGS
    val modalOpen =
        dialog != null ||
            modelAdd ||
            modelConnect ||
            modelHistory ||
            selectedActivity != null ||
            addressWallet != null ||
            removeAccount != null ||
            sourceScanTarget != null ||
            showRefreshDetails ||
            (advancedMode && selectedAsset != null)

    fun goBack() {
        when {
            advancedMode -> returnFromAdvanced()
            selectedAsset != null -> selectedAsset = null
            page == Page.ACCOUNTS || page == Page.SETTINGS -> page = Page.valueOf(modelReturnPage)
        }
    }
    BackHandler(enabled = canGoBack && !modalOpen, onBack = ::goBack)
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).onPreviewKeyEvent { event ->
            if (canGoBack && !modalOpen && event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                goBack()
                true
            } else {
                false
            }
        },
    ) {
        val compact = maxWidth < 700.dp
        val wide = maxWidth >= 1120.dp
        if (modelMode) {
            modelWorkspaceState.SaveableStateProvider("workspace") {
                ModelWorkspace(
                    app = app,
                    host = host,
                    dataRevision = dashboardRevision + revision,
                    page = page,
                    snapshot =
                        snapshot?.takeIf {
                            page !in
                                setOf(
                                    Page.OVERVIEW,
                                    Page.REPORTS,
                                ) ||
                                snapshotFilter.portfolio == selectedPortfolio
                        },
                    selectedPortfolio = if (page == Page.HOME) null else selectedPortfolio,
                    selectedAsset = selectedAsset,
                    chart = if (presenter.state.chartCurrency == snapshot?.currency) chart else emptyList(),
                    chartLoading = presenter.state.chartLoading,
                    chartError = presenter.state.chartError,
                    period = period,
                    appearance = appearance,
                    preferences = preferences,
                    wifiOnlyRefreshSupported = host.wifiOnlyRefreshSupported,
                    assetPreference = assetPreference,
                    refreshing = pricePhase == PriceRefreshPhase.RUNNING,
                    refreshQueued = pricePhase == PriceRefreshPhase.QUEUED,
                    syncStatus = syncStatus,
                    favorites = app.marketFavorites(),
                    onFavorite = { asset ->
                        val favorites = app.marketFavorites()
                        execute("Favorites saved") {
                            app.setMarketFavorites(if (asset in favorites) favorites - asset else favorites + asset)
                        }
                    },
                    onPage = {
                        if (it == Page.ACCOUNTS || it == Page.SETTINGS) modelReturnPage = page.name
                        page = it
                    },
                    onBackAux = { page = Page.valueOf(modelReturnPage) },
                    onPortfolio = { selectedPortfolio = it },
                    onAsset = { selectedAsset = it },
                    onPeriod = { period = it },
                    onAssetPreference = ::saveAssetPreference,
                    onCurrency = { currency ->
                        val portfolio = snapshot?.ownership?.portfolios?.find { it.id == selectedPortfolio }
                        execute("Display currency saved") {
                            if (portfolio == null) {
                                app.setCurrency(currency)
                            } else {
                                app.savePortfolio(portfolio.copy(valuationCurrency = currency.value))
                            }
                        }
                    },
                    onRefresh = ::refreshPrices,
                    onAdd = {
                        modelEditingId = null
                        modelManual = false
                        modelAdd = true
                    },
                    onAdvanced = { destination ->
                        val current = presenter.state
                        advancedOrigin =
                            listOf(
                                current.page.name,
                                current.detailAsset?.value.orEmpty(),
                                current.search,
                                current.kind?.name.orEmpty(),
                                current.activityStatus?.name.orEmpty(),
                                current.activityNetwork.orEmpty(),
                                current.selectedAccount?.value.orEmpty(),
                                current.offset.toString(),
                                current.activityAsset?.value.orEmpty(),
                                (destination == Page.ACTIVITY && selectedAsset != null).toString(),
                                current.selectedPortfolio?.value.orEmpty(),
                            )
                        if (current.page == Page.HOME && current.detailAsset != null) selectedPortfolio = null
                        if (destination == Page.ACTIVITY && selectedAsset != null) {
                            presenter.dispatch(ProductEvent.AssetActivity(selectedAsset!!.asset))
                        } else {
                            page = destination
                        }
                        advancedMode = true
                    },
                    onRefreshDetails = {
                        showRefreshDetails = true
                        refreshDetails = app.automaticPriceStatus() ?: "Price refresh has not run."
                    },
                    onSyncDetails = { modelHistory = true },
                    onConnectBitcoin = { modelConnect = true },
                    onAccountChanged = { execute("Account updated; saved history retained") {} },
                    onAccountTool = { tool ->
                        dialog =
                            when (tool) {
                                AccountTool.RULES -> "rule"
                                AccountTool.RECONCILIATION -> "reconcile"
                                AccountTool.UTXO -> "utxo"
                            }
                    },
                    onAutomatic = { enabled ->
                        val updated = preferences.copy(automaticRefresh = enabled)
                        execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
                        preferences = updated
                    },
                    onWifi = { enabled ->
                        val updated = preferences.copy(wifiOnly = enabled)
                        execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
                        preferences = updated
                    },
                    onTransaction = { selectedActivity = it },
                    statusContent = { SnackbarHost(snackbar, Modifier.testTag("status-message")) },
                    onManual = { asset ->
                        modelEditingId = null
                        manualAsset = asset?.asset
                        modelManual = true
                        modelAdd = true
                    },
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                if (!compact) {
                    WorkspaceRail(page, { page = it }, wide, { dialog = "add" })
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = Paper,
                    snackbarHost = {
                        SnackbarHost(
                            snackbar,
                            Modifier.testTag("status-message").onSizeChanged { snackbarHeight = it.height },
                        )
                    },
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (advancedMode) {
                                TextButton(onClick = ::returnFromAdvanced) { Text("Back") }
                            } else if (page !in compactDestinations) {
                                TextButton(
                                    onClick = { page = Page.OVERVIEW },
                                    modifier =
                                        Modifier.testTag("screen-back").semantics {
                                            contentDescription =
                                                "Back to portfolio"
                                        },
                                ) { Text("‹") }
                            }
                            Text(page.label, modifier = Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = ::refreshPrices,
                                enabled = pricePhase == PriceRefreshPhase.IDLE,
                                modifier = Modifier.semantics { contentDescription = "Refresh prices" },
                            ) {
                                Text(
                                    if (pricePhase ==
                                        PriceRefreshPhase.QUEUED
                                    ) {
                                        "Queued"
                                    } else if (pricePhase ==
                                        PriceRefreshPhase.RUNNING
                                    ) {
                                        "Refreshing…"
                                    } else {
                                        "↻"
                                    },
                                )
                            }
                            Box {
                                TextButton(
                                    onClick = { menuOpen = true },
                                    modifier =
                                        Modifier.testTag("workspace-menu").semantics {
                                            contentDescription =
                                                "Open menu"
                                        },
                                ) {
                                    RevisedIcon("menu", Modifier.size(23.dp))
                                }
                                DropdownMenu(menuOpen, { menuOpen = false }) {
                                    listOf(Page.ACCOUNTS, Page.SETTINGS, Page.MORE).forEach { destination ->
                                        DropdownMenuItem(text = { Text(destination.label) }, onClick = {
                                            menuOpen = false
                                            page = destination
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Refresh details") }, onClick = {
                                        menuOpen = false
                                        showRefreshDetails = true
                                        refreshDetails =
                                            app.automaticPriceStatus() ?: "Pull down to refresh saved prices across portfolios."
                                    })
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (page == Page.OVERVIEW) {
                            FloatingActionButton(
                                onClick = { dialog = "add" },
                                modifier = Modifier.testTag("add-action").semantics { contentDescription = "Add to portfolio" },
                            ) { Text("+", fontSize = 28.sp) }
                        }
                    },
                    bottomBar = {
                        if (compact) {
                            CompactNavigation(page, { page = it }, { dialog = "add" })
                        }
                    },
                ) { insets ->
                    val data =
                        snapshot?.takeIf {
                            page !in setOf(Page.OVERVIEW, Page.REPORTS) || snapshotFilter.portfolio == selectedPortfolio
                        }
                    val activityAccounts =
                        remember(data, snapshotFilter) {
                            data?.let { activityAccountScope(it.ownership, snapshotFilter) }.orEmpty()
                        }
                    PullToRefreshBox(
                        isRefreshing = pricePhase == PriceRefreshPhase.RUNNING,
                        onRefresh = ::refreshPrices,
                        modifier = Modifier.padding(insets),
                    ) {
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = snackbarInset)
                                .testTag("page-content"),
                            state =
                                when (page) {
                                    Page.HOME -> homeScroll
                                    Page.MORE -> moreScroll
                                    Page.OVERVIEW -> overviewScroll
                                    Page.ACTIVITY -> activityScroll
                                    Page.ACCOUNTS -> accountScroll
                                    Page.REPORTS -> reportScroll
                                    Page.SETTINGS -> settingsScroll
                                },
                            contentPadding =
                                PaddingValues(
                                    horizontal = if (compact) PolionSpacing.compactGutter else PolionSpacing.wideGutter,
                                    vertical = if (compact) 20.dp else 28.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            if (page == Page.ACCOUNTS) item { SyncBanner(syncStatus, data?.sourceStatus, { dialog = "sync" }) }
                            if (data?.sampleData == true) item { StatusPill("EXAMPLE DATA · stored only on this device", false) }
                            if (data == null) {
                                item { EmptyCard("Opening your portfolio", "Your local data stays on this device.") }
                            } else {
                                when (page) {
                                    Page.HOME -> {
                                        item {
                                            RevisedMain(app, data) { coin ->
                                                selectedAsset = data.valuations.find { it.asset == coin.asset } ?: coin.detailValuation()
                                            }
                                        }
                                    }
                                    Page.MORE -> {
                                        item {
                                            Panel {
                                                Text("Advanced tools", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                                listOf(Page.ACCOUNTS, Page.ACTIVITY, Page.REPORTS, Page.SETTINGS).forEach { destination ->
                                                    OutlinedButton(
                                                        onClick = { page = destination },
                                                        modifier = Modifier.fillMaxWidth().testTag("more-${destination.name}"),
                                                    ) {
                                                        Text(destination.label)
                                                    }
                                                }
                                            }
                                        }
                                        item { PrivacyFootnote() }
                                    }
                                    Page.OVERVIEW -> {
                                        item {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(
                                                    Modifier.weight(1f),
                                                ) { PortfolioSelector(data, selectedPortfolio) { selectedPortfolio = it } }
                                                var currencyMenu by remember { mutableStateOf(false) }
                                                Box {
                                                    TextButton(onClick = { currencyMenu = true }) { Text("${data.currency.value} ⌄") }
                                                    DropdownMenu(currencyMenu, { currencyMenu = false }) {
                                                        listOf("USD", "EUR", "GBP").forEach { currency ->
                                                            DropdownMenuItem(text = { Text(currency) }, onClick = {
                                                                currencyMenu = false
                                                                execute("Display currency saved") { app.setCurrency(AssetId(currency)) }
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        item {
                                            OutlinedButton(
                                                onClick = { page = Page.ACCOUNTS },
                                                modifier = Modifier.testTag("manage-accounts"),
                                            ) { Text("Manage accounts") }
                                        }
                                        val balance: @Composable () -> Unit = {
                                            BalanceHero(
                                                data,
                                                chart,
                                                presenter.state.chartLoading,
                                                presenter.state.chartError,
                                                compact,
                                                period,
                                                { period = it },
                                                { dialog = "price" },
                                            )
                                        }
                                        val holdings: @Composable () -> Unit = {
                                            AssetsCard(
                                                data,
                                                assetPreference,
                                                busy,
                                                ::saveAssetPreference,
                                                { selectedAsset = it },
                                                { dialog = "price" },
                                            )
                                        }
                                        if (wide) {
                                            item(key = "portfolio-grid") {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                                    Column(Modifier.weight(1f)) { balance() }
                                                    Column(Modifier.weight(1.2f)) { holdings() }
                                                }
                                            }
                                        } else {
                                            item(key = "portfolio-balance") { balance() }
                                            item(key = "portfolio-holdings") { holdings() }
                                        }
                                        item { Spacer(Modifier.height(64.dp)) }
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
                                        presenter.state.activityAsset?.let { asset ->
                                            item {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Asset: ${asset.value}", modifier = Modifier.weight(1f))
                                                    TextButton(
                                                        onClick = { presenter.dispatch(ProductEvent.ClearActivityAsset) },
                                                    ) { Text("Clear asset") }
                                                }
                                            }
                                        }
                                        item {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FilterActivityStatus(activityStatus) { activityStatus = it }
                                                FilterActivityNetwork(data.activityNetworks, activityNetwork) { activityNetwork = it }
                                            }
                                        }
                                        if (snapshotFilter != presenter.state.requestedFilter()) {
                                            item {
                                                Text(
                                                    if (presenter.state.snapshotLoading) {
                                                        "Updating activity results…"
                                                    } else {
                                                        "Activity results are unavailable for these filters."
                                                    },
                                                    color = Muted,
                                                )
                                            }
                                        } else {
                                            item {
                                                Text(
                                                    "${data.activityTotal} activities · matching filters",
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
                                                    ActivityRow(it, data, compact, activityAccounts) {
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
                                                        "${if (data.activityTotal == 0) 0 else offset + 1}–${minOf(
                                                            offset + 50,
                                                            data.activityTotal,
                                                        )}",
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
                                    }
                                    Page.ACCOUNTS -> {
                                        item { HealthCard(data, { dialog = "sync" }, { dialog = "utxo" }) }
                                        item { OutlinedButton(onClick = { dialog = "sync" }) { Text("Refresh wallet history") } }
                                        item { SectionTitle("Watch-only accounts", "+ Add") { dialog = "add" } }
                                        item {
                                            Text(
                                                "Manage accounts and their public sources across all portfolios. Archive an " +
                                                    "account to pause tracking and hide it from active portfolios; saved history stays on this device.",
                                                color = Muted,
                                            )
                                        }
                                        if (data.ownership.accounts.isEmpty()) {
                                            item {
                                                EmptyCard(
                                                    "Your keys stay yours",
                                                    "Choose a supported public Bitcoin source. Polion never needs your recovery " +
                                                        "phrase or private keys.",
                                                    "Add your first source",
                                                ) {
                                                    dialog =
                                                        "add"
                                                }
                                            }
                                        }
                                        items(
                                            data.ownership.accounts.filter { "polion:archived" !in it.tags },
                                            key = { it.id.value },
                                        ) { account ->
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
                                                data.ownership.targets
                                                    .filter { it.accountId == account.id && it.walletId == null }
                                                    .forEach { target ->
                                                        HorizontalDivider(color = Line)
                                                        Text(target.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                                        TextButton(onClick = { sourceScanTarget = target }) { Text("View scan details") }
                                                        if (target.role ==
                                                            io.polion.core.ownership.TargetRole.WALLET
                                                        ) {
                                                            TextButton(
                                                                onClick = { addressWallet = target },
                                                            ) { Text("View derived addresses") }
                                                        }
                                                        if (target.role ==
                                                            io.polion.core.ownership.TargetRole.WALLET
                                                        ) {
                                                            TextButton(onClick = {
                                                                host.discoverWallet(target.id) {
                                                                    message =
                                                                        it
                                                                    ; revision++
                                                                }
                                                            }, enabled = syncStatus.phase != SyncPhase.SYNCING) {
                                                                Text(
                                                                    "Discover wallet addresses",
                                                                )
                                                            }
                                                        }
                                                        Text(target.identity.canonicalKey, color = Muted, fontSize = 12.sp)
                                                        StatusPill(
                                                            if (target.enabled) "Watching · ${target.identity.kind}" else "Paused",
                                                            target.enabled,
                                                        )
                                                    }
                                                TextButton(
                                                    onClick = { removeAccount = account },
                                                    enabled = !busy && syncStatus.phase != SyncPhase.SYNCING,
                                                    modifier = Modifier.testTag("archive-account-${account.id.value}"),
                                                ) { Text("Archive account", fontSize = 12.sp) }
                                                data.balances.quantities.filterKeys { it.account == account.id }.forEach { (key, units) ->
                                                    Text(
                                                        data.valuations.find { it.asset == key.asset }?.metadata?.let {
                                                            "${formatUnits(units, it.decimals)} ${it.symbol}"
                                                        } ?: "$units atomic units (${key.asset.value})",
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                }
                                            }
                                        }
                                        if (data.ownership.accounts.any { "polion:archived" in it.tags }) {
                                            item { SectionTitle("Archived accounts") }
                                            items(
                                                data.ownership.accounts.filter { "polion:archived" in it.tags },
                                                key = { "archived:${it.id.value}" },
                                            ) { archived ->
                                                Panel {
                                                    Text(archived.label, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        "Tracking paused. Saved activity and accounting decisions are retained.",
                                                        fontSize = 12.sp,
                                                        color = Muted,
                                                    )
                                                    OutlinedButton(
                                                        onClick = {
                                                            execute(
                                                                "Account restored",
                                                            ) { app.archiveAccount(archived.id, false) }
                                                        },
                                                        enabled = !busy && syncStatus.phase != SyncPhase.SYNCING,
                                                        modifier = Modifier.testTag("restore-account-${archived.id.value}"),
                                                    ) { Text("Restore account") }
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
                                                    "${portfolio.valuationCurrency} · " +
                                                        if (portfolio.accountIds.isEmpty()) {
                                                            "All accounts"
                                                        } else {
                                                            "${portfolio.accountIds.size} accounts"
                                                        },
                                                    color = Muted,
                                                )
                                            }
                                        }
                                    }
                                    Page.REPORTS -> {
                                        item {
                                            Panel {
                                                Text("Investment returns · —", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                                Text(
                                                    "Money-flow-adjusted performance is unavailable. Portfolio value changes include deposits and withdrawals.",
                                                    color = Muted,
                                                )
                                            }
                                        }
                                        item { PortfolioSelector(data, selectedPortfolio) { selectedPortfolio = it } }
                                        item {
                                            BalanceHero(
                                                data,
                                                chart,
                                                presenter.state.chartLoading,
                                                presenter.state.chartError,
                                                compact,
                                                period,
                                                {
                                                    period =
                                                        it
                                                },
                                                { dialog = "price" },
                                            )
                                        }
                                        item {
                                            AllocationPanel(AssetAllocation.project(data.valuations, data.currency, data.coverage)) {
                                                selectedAsset =
                                                    it
                                            }
                                        }
                                        item { AnalyticsPanel(app, host, selectedPortfolio, revision + hostRevision.toInt()) }
                                        item {
                                            AccountingPanel(
                                                app,
                                                host,
                                                selectedPortfolio,
                                                revision + hostRevision.toInt(),
                                            ) { revision++ }
                                        }
                                        item {
                                            Panel {
                                                Text("Understand your cost basis", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                                Text(
                                                    "FIFO follows the oldest available lots. An incoming transfer does not " +
                                                        "establish a purchase price. Unknown purchase costs remain unknown, and pending activity is excluded from confirmed reports.",
                                                    color = Muted,
                                                    lineHeight = 22.sp,
                                                )
                                                Text(
                                                    "Values use saved price observations at each point in time. Changes in the " +
                                                        "chart may reflect deposits and withdrawals, not investment performance.",
                                                    color = Muted,
                                                    fontSize = 13.sp,
                                                )
                                                OutlinedButton(onClick = {
                                                    execute("Export ready") {
                                                        val csv = app.exportCsv(ActivityFilter(portfolio = selectedPortfolio))
                                                        host.saveFile("polion-activities.csv", csv.encodeToByteArray()) {
                                                            message =
                                                                it
                                                        }
                                                    }
                                                }) {
                                                    Text(
                                                        if (selectedPortfolio ==
                                                            null
                                                        ) {
                                                            "↓  Export all active activity CSV"
                                                        } else {
                                                            "↓  Export selected portfolio CSV"
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        item { SectionTitle("Reconciliation", "Review") { dialog = "reconcile" } }
                                        item {
                                            Text(
                                                "${data.duplicateConflicts.size} unresolved duplicate conflicts. Transfer " +
                                                    "suggestions always require your review.",
                                                color = Muted,
                                            )
                                        }
                                    }
                                    Page.SETTINGS -> {
                                        item {
                                            Panel {
                                                Text("Sync settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Automatic refresh", Modifier.weight(1f))
                                                    Switch(preferences.automaticRefresh, { enabled ->
                                                        val updated = preferences.copy(automaticRefresh = enabled)
                                                        execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
                                                        preferences = updated
                                                    }, modifier = Modifier.testTag("automatic-refresh"))
                                                }
                                                Text(
                                                    "Consented wallet history every 30 minutes and prices hourly, subject to " +
                                                        "platform scheduling.",
                                                    color = Muted,
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Wi-Fi-only background refresh", Modifier.weight(1f))
                                                    Switch(preferences.wifiOnly, { enabled ->
                                                        val updated = preferences.copy(wifiOnly = enabled)
                                                        execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
                                                        preferences = updated
                                                    }, modifier = Modifier.testTag("wifi-only-refresh"))
                                                }
                                                Text(
                                                    if (host.wifiOnlyRefreshSupported) {
                                                        "Background jobs use an unmetered network. Manual refresh uses the current connection."
                                                    } else {
                                                        "The Wi-Fi restriction applies to Android background jobs. Desktop " +
                                                            "refresh uses the current connection."
                                                    },
                                                    color = Muted,
                                                )
                                                Text(
                                                    "Pull down to refresh prices across configured portfolios. Wallet history " +
                                                        "uses the current full scan behavior.",
                                                    color = Muted,
                                                )
                                                OutlinedButton(onClick = { dialog = "sync" }) { Text("Refresh wallet history") }
                                            }
                                        }

                                        item {
                                            Panel {
                                                Text("Make it yours", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                                Text("Appearance", color = Muted, fontSize = 12.sp)
                                                AppearanceSelector(appearance) { selected ->
                                                    execute("Appearance saved") {
                                                        app.setAppearanceTheme(selected.key)
                                                        onAppearance(selected)
                                                    }
                                                }
                                                Text(
                                                    "System follows your device’s light or dark appearance.",
                                                    color = Muted,
                                                    fontSize = 13.sp,
                                                )
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
                                                    "Public indexers can associate queried addresses with your IP address. " +
                                                        "Choose your own endpoint or stay offline.",
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
                                                    "App lock hides the screen. The local database is stored in plaintext in " +
                                                        "your app data directory; use device or disk encryption for protection at rest.",
                                                    color = Muted,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                        item {
                                            Panel {
                                                Text("Your data, in your hands", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                                Text(
                                                    "Create an encrypted backup before replacing or removing local data. Your " +
                                                        "password cannot be recovered.",
                                                    color = Muted,
                                                    lineHeight = 22.sp,
                                                )
                                                WrapActions(
                                                    listOf(
                                                        "Encrypted backup" to { dialog = "backup" },
                                                        "Restore backup" to { dialog = "restore" },
                                                        "All active activity CSV" to
                                                            {
                                                                execute("Export ready") {
                                                                    host.saveFile(
                                                                        "polion-activities.csv",
                                                                        app.exportCsv().encodeToByteArray(),
                                                                    ) {
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
                                                    "Apply a repeatable classification to matching activity. Per-activity " +
                                                        "corrections take precedence.",
                                                    color = Muted,
                                                )
                                                OutlinedButton(onClick = { dialog = "rule" }) { Text("Manage rules") }
                                            }
                                        }
                                        item {
                                            Panel {
                                                Text("Your setup", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                                Text("Review your local preferences and supported source guidance.", color = Muted)
                                                OutlinedButton(onClick = { onSetup(false) }) { Text("Resume setup") }
                                                TextButton(onClick = { onSetup(true) }) { Text("Restart setup") }
                                                setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
            }
        }
        if (modelConnect && snapshot != null) {
            sourceFlowState.SaveableStateProvider("bitcoin-connect") {
                BitcoinConnectDialog(
                    app,
                    host,
                    snapshot!!,
                    selectedPortfolio,
                    close = { modelConnect = false },
                    onSaved = {
                        sourceFlowState.removeState("bitcoin-connect")
                        execute("Public Bitcoin source saved") {}
                    },
                    notify = { presenter.notify(it) },
                    syncStatus = syncStatus,
                )
            }
        }
        if (modelHistory && snapshot != null) {
            sourceFlowState.SaveableStateProvider("bitcoin-history") {
                BitcoinHistoryDialog(
                    app,
                    host,
                    snapshot!!,
                    syncStatus,
                    close = { modelHistory = false },
                    onSaved = {
                        sourceFlowState.removeState("bitcoin-history")
                        execute("Bitcoin source settings saved") {}
                    },
                    notify = { presenter.notify(it) },
                )
            }
        }
        if (modelAdd && snapshot != null && (modelEditingId == null || modelEditingDraft?.id?.value == modelEditingId)) {
            modelDraftState.SaveableStateProvider(modelDraftScope) {
                TransactionEntry(
                    app,
                    host,
                    snapshot!!,
                    modelManual,
                    manualAsset,
                    modelEditingDraft.takeIf { modelEditingId != null },
                    onManual = { modelManual = true },
                    onClose = { modelAdd = false },
                    onWallet = {
                        modelAdd = false
                        modelConnect = true
                    },
                    onSaved = {
                        modelAdd = false
                        modelDraftState.removeState(modelDraftScope)
                        modelEditingId = null
                        modelEditingDraft = null
                        execute("Transaction saved on this device") {}
                    },
                    onAccountCreated = { execute("Local account created") {} },
                )
            }
        }

        if (refreshDetails != null && showRefreshDetails) {
            AlertDialog(
                onDismissRequest = { showRefreshDetails = false },
                title = { Text("Price refresh") },
                text = { Text(refreshDetails!!) },
                confirmButton = { TextButton(onClick = { showRefreshDetails = false }) { Text("Close") } },
            )
        }
        if (dialog == "utxo") {
            UtxoReviewDialog(app, host) { dialog = null }
        }
        if (dialog == "wallet" && snapshot != null) {
            WalletDialog(
                snapshot!!.copy(
                    sourceEndpoint = sourceDraftEndpoint ?: snapshot!!.sourceEndpoint,
                    sourceConsent =
                        sourceDraftConsent ?: snapshot!!.sourceConsent,
                ),
                app,
                host,
                { dialog = null },
                {
                    message = it
                    revision++
                },
                host::discoverWallet,
            )
        }
        if (dialog != null &&
            dialog != "wallet" &&
            dialog != "add" &&
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
                initialAsset = manualAsset,
            )
        }
        addressWallet?.let { wallet ->
            AlertDialog(
                onDismissRequest = { addressWallet = null },
                title = { Text(wallet.label) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(snapshot!!.ownership.targets.filter { it.walletId == wallet.id }, key = { it.id.value }) { target ->
                            Column {
                                Text(target.label, fontWeight = FontWeight.Medium)
                                Text(target.identity.canonicalKey, fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { addressWallet = null }) { Text("Close") } },
            )
        }
        removeAccount?.let { account ->
            AlertDialog(
                onDismissRequest = { removeAccount = null },
                title = { Text("Archive ${account.label}?") },
                text = {
                    Text(
                        "The account and its subaccounts will be hidden from active portfolios and their public address " +
                            "queries will stop. Saved addresses, activity, and accounting decisions stay on this device. This does not delete the account or its history. Restore it from Archived accounts on this page.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        execute("Account archived; saved history retained") { app.archiveAccount(account.id) }
                        selectedAccount = null
                        removeAccount = null
                    }, enabled = !busy && syncStatus.phase != SyncPhase.SYNCING, modifier = Modifier.testTag("confirm-archive-account")) {
                        Text("Archive account")
                    }
                },
                dismissButton = { TextButton(onClick = { removeAccount = null }) { Text("Keep account") } },
            )
        }
        sourceScanTarget?.let { target -> SourceScanDialog(app, host, target) { sourceScanTarget = null } }
        if (!modelMode) {
            selectedAsset?.let { asset ->
                AssetDialog(
                    asset,
                    snapshot!!,
                    app,
                    host,
                    if (page ==
                        Page.HOME
                    ) {
                        null
                    } else {
                        selectedPortfolio
                    },
                    revision,
                    { selectedAsset = null },
                    {
                        manualAsset = asset.asset
                        selectedAsset = null
                        dialog = "price"
                    },
                    {
                        if (page == Page.HOME) selectedPortfolio = null
                        presenter.dispatch(ProductEvent.AssetActivity(asset.asset))
                    },
                    { activity -> selectedActivity = activity },
                    {
                        execute(
                            "Provider prices selected; manual history retained",
                        ) { app.useProviderPrices(asset.asset, snapshot!!.currency) }
                        selectedAsset = null
                    },
                    onAddTransaction = { assetId ->
                        manualAsset = assetId
                        selectedAsset = null
                        dialog = "activity"
                    },
                    onRefreshPrices = ::refreshPrices,
                )
            }
        }
        selectedActivity?.let { item ->
            ActivityDialog(
                item,
                snapshot!!,
                {
                    if (item.original.manual) {
                        selectedManualDraft?.let { draft ->
                            val executionTime = kotlin.time.Instant.fromEpochMilliseconds(draft.atMillis)
                            Text("${draft.type.name} · ${draft.asset.value}/${draft.quote.value} · $executionTime")
                            draft.unitPrice?.let { Text("Execution / acquisition evidence: ${quotePrice(it, draft.quote)} per unit") }
                            draft.feeUnitPrice?.let {
                                Text(
                                    "Fee conversion evidence: ${quotePrice(it, draft.quote)} per ${draft.feeAsset?.value}",
                                )
                            }
                            Text("Fees are separate expense disposals at zero proceeds. Trade consideration excludes fees.")
                        }
                    } else {
                        ActivityEvidence(app, host, item.activity.id, if (page == Page.HOME) null else selectedPortfolio)
                    }
                },
                { selectedActivity = null },
                edit =
                    if (item.original.manual && selectedManualCanEdit && selectedManualDraft?.id == item.activity.id) {
                        {
                            modelEditingId = item.activity.id.value
                            modelEditingDraft = null
                            modelManual = true
                            modelAdd = true
                            selectedActivity = null
                        }
                    } else {
                        null
                    },
                reverse =
                    if (item.original.manual) {
                        {
                            execute("Manual transaction reversed; history retained") { app.reverseManualTransaction(item.activity.id) }
                            selectedActivity = null
                        }
                    } else {
                        null
                    },
            ) { overlay ->
                execute("Correction saved; original evidence preserved") { app.saveOverlay(overlay) }
                selectedActivity =
                    null
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceHero(
    data: PortfolioSnapshot,
    points: List<HistoryPoint>,
    chartLoading: Boolean,
    chartError: Boolean,
    compact: Boolean,
    period: Int,
    onPeriod: (Int) -> Unit,
    onPrice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Corners)
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (compact) 22.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("PRICED VALUE", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium)
            Text(data.currency.value, color = Muted, fontSize = 12.sp)
        }
        val pricing = pricingSummary(data.valuations)
        Text(
            pricing.total?.let { money(it, data.currency) } ?: "—",
            color = Ink,
            fontSize = if (compact) 34.sp else 44.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1).sp,
            maxLines = 2,
        )
        if (pricing.stale > 0) Text(stalePriceMessage(pricing.stale), color = Muted, fontSize = 12.sp)
        if (chartLoading && points.isNotEmpty()) Text("Updating history…", color = Muted, fontSize = 12.sp)
        val firstValue = points.firstOrNull()?.let(::historyTotal)
        val lastValue = points.lastOrNull()?.let(::historyTotal)
        Text(
            "Value change · " + if (firstValue != null && lastValue != null) money(lastValue - firstValue, data.currency) else "—",
            color = Muted,
            fontSize = 12.sp,
        )
        ValueChart(
            points,
            Modifier.fillMaxWidth().height(if (compact) 112.dp else 150.dp),
            dark = false,
            loading = chartLoading,
            error = chartError,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to "1D", 7 to "1W", 30 to "1M", 90 to "3M", 365 to "1Y", 0 to "ALL").forEach { (days, label) ->
                Text(
                    label,
                    color =
                        if (period ==
                            days
                        ) {
                            Ink
                        } else {
                            Muted
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
                                    Mint
                                } else {
                                    Color.Transparent
                                },
                            ).clickable { onPeriod(days) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Text("Historical portfolio value · not investment return", color = Muted, fontSize = 11.sp)
        var chartDetails by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { chartDetails = !chartDetails }) { Text(if (chartDetails) "Hide chart details" else "Chart details") }
        if (chartDetails && !chartLoading && !chartError) HistoryInspector(points, data.currency)
        val missing = pricing.missing
        if (missing >
            0
        ) {
            Text(
                "$missing unpriced asset${if (missing == 1) "" else "s"} excluded · Add a price →",
                color = Orange,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onPrice).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun ValueChart(
    points: List<HistoryPoint>,
    modifier: Modifier,
    dark: Boolean,
    loading: Boolean,
    error: Boolean,
) {
    // Conversion to Float is exclusively for drawing coordinates; authoritative values remain exact.
    val values =
        points.map { point ->
            historyTotal(point)
                ?.toString()
                ?.toDoubleOrNull()
        }
    if (values.none { it != null }) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                when {
                    loading -> "Loading history…"
                    error -> "History could not be loaded"
                    points.isEmpty() -> "No history available yet"
                    points.all { it.valuations.isEmpty() } -> "No tracked holdings in this period"
                    else -> "Fresh historical prices are needed to show value over time"
                },
                color = if (dark) Color(0xFFAAC6B7) else Muted,
                fontSize = 12.sp,
            )
        }
    } else {
        val chartStroke = if (dark) Color(0xFFA9D6B6) else Teal
        Canvas(
            modifier.semantics {
                contentDescription =
                    "Portfolio value history chart; ${values.count {
                        it != null
                    }} available points, ${values.count { it == null }} unavailable points"
            },
        ) {
            val finite = values.filterNotNull().filter { it.isFinite() }
            if (finite.isEmpty()) return@Canvas
            val min = finite.min()
            val max = finite.max()
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val stroke = chartStroke
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetsCard(
    data: PortfolioSnapshot,
    preference: AssetListPreference,
    busy: Boolean,
    onPreference: (AssetListPreference) -> Unit,
    onAsset: (AssetValuation) -> Unit,
    onPrice: () -> Unit,
) {
    val sortByValue = preference.sort == AssetSort.VALUE
    val sorted =
        remember(data.valuations, preference) {
            displayedAssets(data.valuations, preference)
        }
    Panel {
        SectionTitle("Your assets", "Prices") { onPrice() }
        if (data.valuations.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sortByValue, enabled = !busy, onClick = {
                    onPreference(preference.copy(sort = AssetSort.VALUE))
                }, label = { Text("Value") })
                FilterChip(selected = !sortByValue, enabled = !busy, onClick = {
                    onPreference(preference.copy(sort = AssetSort.NAME))
                }, label = { Text("Name") })
                FilterChip(selected = preference.hideEmpty, enabled = !busy, onClick = {
                    onPreference(preference.copy(hideEmpty = !preference.hideEmpty))
                }, label = { Text("Hide empty") }, modifier = Modifier.testTag("hide-empty-assets"))
                FilterChip(selected = preference.hideSmall, enabled = !busy, onClick = {
                    onPreference(preference.copy(hideSmall = !preference.hideSmall))
                }, label = { Text("Hide under 1 ${data.currency.value}") }, modifier = Modifier.testTag("hide-small-assets"))
            }
            if (preference.hideSmall) {
                Text(
                    "Small positions with fresh prices are hidden from this list only. Stale and unpriced positions remain visible.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
        if (data.valuations.isEmpty()) {
            Text(
                "Confirmed assets will appear after you add activity or sync an account.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 21.sp,
            )
        }
        if (data.valuations.isNotEmpty() && sorted.isEmpty()) {
            Text("No positions match these display filters. Turn off a filter to see them.", color = Muted, fontSize = 13.sp)
        }
        sorted.forEach { asset ->
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
                        "${asset.metadata?.let {
                            "${formatUnits(
                                asset.amount.atomicUnits,
                                it.decimals,
                            )} ${it.symbol}"
                        } ?: "${asset.amount.atomicUnits} atomic units"}",
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                    asset.price?.let { quote ->
                        Text("${quotePrice(quote.price.value, quote.price.quoteAssetId)} per unit", color = Muted, fontSize = 11.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(asset.value?.let { money(it, data.currency) } ?: "Unpriced", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        asset.priceStatus.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() } +
                            if (asset.price?.manual == true) " · manual quote" else "",
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
    onUtxoReview: () -> Unit,
) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Data health", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("◎", color = Teal, fontSize = 22.sp)
        }
        Text("Clarity before confidence.", color = Muted, fontSize = 13.sp)
        data.sourceStatus.lastSuccessfulRefresh?.let { Text("Last saved sync: ${date(it)} UTC", fontSize = 11.sp, color = Muted) }
        if (data.sourceStatus.utxoMismatchCount > 0) {
            Text(
                "UTXO comparison needs review: ${data.sourceStatus.utxoMismatchCount} differences or missing parents. " +
                    "History coverage remains partial.",
                color = Orange,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onUtxoReview, contentPadding = PaddingValues(0.dp)) {
            Text("Review UTXO comparison →", fontSize = 12.sp)
        }
        data.sourceStatus.lastError?.let { Text("Sync incomplete · saved data retained", color = Orange, fontSize = 12.sp) }
        HorizontalDivider(color = Line)
        HealthLine("History", if (data.coverage.name == "COMPLETE") "Complete" else "Unverified", data.coverage.name == "COMPLETE")
        HealthLine("Pending activity", data.pendingCount.toString(), false)
        val missing = pricingSummary(data.valuations).missing
        HealthLine("Unpriced assets", missing.toString(), missing == 0)
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
            .background(MaterialTheme.colorScheme.surface)
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
                WarningSurface
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
        color = if (positive) Teal else Orange,
        fontSize = 10.sp,
        modifier =
            Modifier
                .clip(
                    RoundedCornerShape(6.dp),
                ).background(if (positive) Mint else WarningSurface)
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
    accounts: Set<AccountId>,
    onClick: () -> Unit,
) {
    val a = item.activity
    val amounts = activityRowAmounts(a, accounts)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(16.dp),
            ).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Paper), contentAlignment = Alignment.Center) {
            Text(
                amounts.directionSymbol,
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
            amounts.quantities.forEach { (asset, quantity) ->
                Text(
                    "${if (quantity.sign > 0) "+" else ""}${data.valuations.find { it.asset == asset }?.metadata?.let {
                        "${formatUnits(quantity, it.decimals)} ${it.symbol}"
                    } ?: "$quantity atomic units (${asset.value})"}",
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

@Composable
private fun FilterActivityStatus(
    selected: ActivityStatus?,
    onSelect: (ActivityStatus?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("activity-status-filter")) {
            Text("Status: ${selected?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All"} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All statuses") }, onClick = {
                onSelect(null)
                expanded = false
            }, modifier = Modifier.testTag("status-all"))
            ActivityStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(status)
                        expanded = false
                    },
                    modifier = Modifier.testTag("status-${status.name}"),
                )
            }
        }
    }
}

@Composable
private fun FilterActivityNetwork(
    networks: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("activity-network-filter")) {
            Text("Network: ${selected ?: "All"} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Networks from recorded sources across all accounts") }, onClick = {}, enabled = false)
            DropdownMenuItem(text = { Text("All networks, including manual") }, onClick = {
                onSelect(null)
                expanded = false
            })
            networks.forEach { network ->
                DropdownMenuItem(text = { Text(network) }, onClick = {
                    onSelect(network)
                    expanded = false
                }, modifier = Modifier.testTag("network-$network"))
            }
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
            data.ownership.accounts.filter { "polion:archived" !in it.tags }.forEach { a ->
                DropdownMenuItem({ Text(a.label) }, {
                    onSelect(a.id)
                    expanded =
                        false
                })
            }
        }
    }
}

@Composable internal fun PortfolioSelector(
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

private fun symbol(
    asset: AssetId,
    data: PortfolioSnapshot,
) = data.valuations
    .find { it.asset == asset }
    ?.metadata
    ?.symbol ?: asset.value.substringAfterLast(':').uppercase()

internal fun formatUnits(
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

internal fun money(
    value: ExactDecimal,
    currency: AssetId,
): String {
    if (currency.value == "USD" && value.coefficient.sign != 0) {
        val magnitude = if (value.coefficient.sign < 0) ExactDecimal(-value.coefficient, value.scale) else value
        if (magnitude < ExactDecimal.parse("0.01")) return (if (value.coefficient.sign < 0) "-" else "") + "<$0.01"
    }
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

/** Running, failed or interrupted work is shown; a finished success is not kept as a permanent banner. */
internal fun shouldShowSyncBanner(
    status: SyncStatus,
    saved: SourceStatus?,
): Boolean = status.phase == SyncPhase.SYNCING || status.phase == SyncPhase.ERROR || saved?.interrupted == true

@Composable
internal fun SyncBanner(
    status: SyncStatus,
    saved: SourceStatus?,
    onDetails: () -> Unit,
) {
    val running = status.phase == SyncPhase.SYNCING
    val operation =
        when (status.operation) {
            SyncOperation.HISTORY -> "Sync"
            SyncOperation.DISCOVERY -> "Wallet discovery"
            SyncOperation.ORDER -> "Order verification"
            SyncOperation.PRICES -> "Price import"
        }
    val title =
        when (status.phase) {
            SyncPhase.SYNCING -> if (status.operation == SyncOperation.HISTORY) "Syncing…" else "$operation in progress"
            SyncPhase.SUCCESS -> "$operation complete"
            SyncPhase.ERROR -> "$operation needs attention"
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
                    WarningSurface
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
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal, trackColor = MaterialTheme.colorScheme.surface)
            if (status.operation == SyncOperation.HISTORY || status.operation == SyncOperation.DISCOVERY) {
                Text("History is being fetched. Saved balances update after the full scan is checked.", fontSize = 11.sp, color = Muted)
            }
        }
        if (!running &&
            saved?.lastError != null
        ) {
            Text("Previous sync incomplete. Saved history has been retained.", fontSize = 11.sp, color = Orange)
        }
    }
}
