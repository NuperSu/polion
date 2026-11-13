package io.valroot.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.GeneralPreferences
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.Account
import io.valroot.core.sources.SourceWorkflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ValrootApp(
    application: PortfolioApplication,
    platform: PlatformActions,
    locked: Boolean = false,
) {
    var appearance by remember(application) { mutableStateOf(AppearanceTheme.fromKey(application.settings.theme())) }
    val preferenceRevision by platform.dataRevision.collectAsState()
    LaunchedEffect(application, preferenceRevision) {
        appearance = AppearanceTheme.fromKey(application.settings.theme())
    }
    ValrootTheme(appearance) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            // The product stays composed under the lock, so in-progress forms and selections survive a lock cycle.
            OnboardingProduct(application, platform, appearance) { selected -> appearance = selected }
            if (locked) {
                Surface(Modifier.fillMaxSize(), color = Paper) { LockScreen(platform) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Product(
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
    // Connect Wallet asks which kind of source to track, then opens the Bitcoin or the EVM flow.
    var connectChooser by rememberSaveable { mutableStateOf(false) }
    var modelConnect by rememberSaveable { mutableStateOf(false) }
    var modelEvmConnect by rememberSaveable { mutableStateOf(false) }

    /** The exchange whose connect flow is open, by ID. */
    var exchangeConnect by rememberSaveable { mutableStateOf<String?>(null) }
    var modelHistory by rememberSaveable { mutableStateOf(false) }
    val sourceFlowState = rememberSaveableStateHolder()
    var modelAdd by rememberSaveable { mutableStateOf(false) }
    var csvImport by rememberSaveable { mutableStateOf(false) }
    var modelEditingId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelEditingDraft by remember { mutableStateOf<io.valroot.core.application.TransactionDraft?>(null) }
    LaunchedEffect(modelEditingId) {
        modelEditingDraft =
            modelEditingId?.let { id ->
                withContext(host.dispatcher) {
                    app.transactions.draft(
                        io.valroot.core.model
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
    var automaticDisclosure by remember { mutableStateOf(false) }
    val preferenceRevision by host.dataRevision.collectAsState()
    LaunchedEffect(app, host, preferenceRevision) {
        preferences = withContext(host.dispatcher) { host.refreshPreferences() }
    }
    var refreshDetails by remember { mutableStateOf<String?>(null) }
    // Preferences start at defaults and load on the database dispatcher, never on the UI thread.
    var assetPreference by remember(app) { mutableStateOf(AssetListPreference()) }
    var marketFavorites by remember(app) { mutableStateOf(emptySet<AssetId>()) }
    var page by presenter.field({ it.page }) { presenter.dispatch(ProductEvent.Navigate(it)) }
    val snapshot = presenter.state.snapshot
    val snapshotFilter = presenter.state.snapshotFilter
    var revision by presenter.field({ it.revision }) { presenter.update { copy(revision = it) } }
    val dashboardRevision by host.dataRevision.collectAsState()
    var generalPreferences by remember(app) { mutableStateOf(GeneralPreferences()) }
    var biometric by remember(host) { mutableStateOf(host.biometricUnlock()) }
    var hasCoingeckoKey by remember(app) { mutableStateOf(false) }
    var hasAlchemyKey by remember(app) { mutableStateOf(false) }
    var hasOneInchKey by remember(app) { mutableStateOf(false) }
    var lockDelay by remember(host) { mutableStateOf(host.lockDelayMillis) }
    LaunchedEffect(app, dashboardRevision, revision) {
        assetPreference = withContext(host.dispatcher) { app.settings.assetList() }
        marketFavorites = withContext(host.dispatcher) { app.market.favorites() }
        generalPreferences = withContext(host.dispatcher) { app.settings.general() }
        hasCoingeckoKey = withContext(host.dispatcher) { app.market.coingeckoApiKey() != null }
        hasAlchemyKey = withContext(host.dispatcher) { app.evm.alchemyApiKey() != null }
        hasOneInchKey = withContext(host.dispatcher) { app.evm.oneInchApiKey() != null }
        biometric = host.biometricUnlock()
    }
    // The saved landing page applies once per launch, and only if nothing has navigated away from Main yet.
    var landingApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(app) {
        if (!landingApplied) {
            val landing = withContext(host.dispatcher) { app.settings.general().landing }
            landingApplied = true
            if (page == Page.HOME) {
                page =
                    when (landing) {
                        LandingPage.MAIN -> Page.HOME
                        LandingPage.PORTFOLIO -> Page.OVERVIEW
                        LandingPage.INSIGHTS -> Page.REPORTS
                    }
            }
        }
    }
    LaunchedEffect(revision) {
        if (revision != 0) onLocalDataChanged()
    }
    // Workflow dialogs change automatic refresh through the same action, so the cached preferences reload after each change.
    LaunchedEffect(revision) {
        preferences = withContext(host.dispatcher) { host.refreshPreferences() }
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
    var selectedManualDraft by remember { mutableStateOf<io.valroot.core.application.TransactionDraft?>(null) }
    var selectedManualCanEdit by remember { mutableStateOf(false) }
    LaunchedEffect(selectedActivity?.activity?.id) {
        selectedManualDraft = null
        selectedManualCanEdit = false
        selectedActivity?.takeIf { it.original.manual }?.let { item ->
            val (draft, overlay) =
                withContext(host.dispatcher) {
                    app.transactions.draft(item.activity.id) to
                        app.transactions.overlay(item.activity.id)
                }
            selectedManualDraft = draft
            selectedManualCanEdit = draft != null && (overlay == null || overlay.amounts.isEmpty() && overlay.kind == null)
        }
    }
    var addressWallet by presenter.field({ it.addressWallet }) { presenter.update { copy(addressWallet = it) } }
    var sourceScanTarget by remember { mutableStateOf<io.valroot.core.ownership.TrackingTarget?>(null) }
    var removeAccount by presenter.field({ it.removeAccount }) { presenter.update { copy(removeAccount = it) } }
    var selectedAsset by presenter.field({ state ->
        state.snapshot?.valuations?.find { it.asset == state.detailAsset }
            ?: state.snapshot
                ?.marketCoins
                ?.find { it.asset == state.detailAsset }
                ?.detailValuation()
            // A coin opened from the CoinGecko lists is not in the snapshot; it stays open across reloads.
            ?: state.selectedAsset?.takeIf { it.asset == state.detailAsset && it.price?.id?.startsWith("coingecko:") == true }
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
            val result = withContext(host.dispatcher) { app.prices.automaticStatus() }
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
            val result = withContext(host.dispatcher) { app.prices.automaticStatus() }
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

    val priceScope = rememberCoroutineScope()
    var marketDisclosure by remember { mutableStateOf(false) }
    var marketRefreshing by remember { mutableStateOf(false) }

    /** Asks for consent the first time; [quiet] refreshes report nothing unless they fail. */
    fun refreshMarkets(
        maxAgeMillis: Long = 60_000L,
        quiet: Boolean = false,
    ) {
        if (marketRefreshing) return
        marketRefreshing = true
        priceScope.launch {
            val consent = runCatching { withContext(host.dispatcher) { app.market.consent() } }.getOrDefault(false)
            if (!consent) {
                marketRefreshing = false
                if (!quiet) marketDisclosure = true
                return@launch
            }
            host.refreshMarkets(maxAgeMillis) { message ->
                marketRefreshing = false
                // Success messages start with "Market data"; a quiet refresh reports only problems.
                if (!quiet || !message.startsWith("Market data")) presenter.notify(message)
            }
        }
    }

    /** Without price consent this opens price setup, unless [promptForConsent] is false. */
    fun refreshPrices(promptForConsent: Boolean = true) {
        if (priceRequested || pricePhase != PriceRefreshPhase.IDLE) return
        priceRequested = true
        priceScope.launch {
            val (allowedCurrencies, consent) =
                withContext(host.dispatcher) {
                    app.prices
                        .automaticCurrencies()
                        .map { it.value }
                        .toSet() to app.prices.automaticConsent()
                }
            val missingCurrencyConsent =
                snapshot?.ownership?.portfolios.orEmpty().any {
                    it.valuationCurrency in setOf("USD", "EUR", "GBP") && it.valuationCurrency !in allowedCurrencies
                }
            if (!promptForConsent && !consent) {
                priceRequested = false
                return@launch
            }
            if (!consent || missingCurrencyConsent) {
                priceRequested = false
                if (missingCurrencyConsent) presenter.notify("Allow other portfolio currencies in price setup to refresh all portfolios.")
                selectedAsset = null
                dialog = "price"
                return@launch
            }
            notifiedPriceResult = null
            host.refreshAutomaticPrices { result ->
                refreshDetails = result
                if (host.priceRefreshStatus.value == PriceRefreshPhase.IDLE && !result.startsWith("Price refresh queued")) {
                    priceRequested = false
                    presenter.notify(result)
                }
            }
        }
    }

    fun showPriceRefreshDetails(fallback: String) {
        showRefreshDetails = true
        priceScope.launch { refreshDetails = withContext(host.dispatcher) { app.prices.automaticStatus() } ?: fallback }
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

    fun changeAutomaticRefresh(enabled: Boolean) {
        preferences = preferences.copy(automaticRefresh = enabled)
        execute("Refresh settings saved") { applyAutomaticRefresh(app, host, enabled) }
    }

    fun onAutomaticSwitch(enabled: Boolean) {
        // Turning automatic refresh on sends data to Kraken and the wallet provider, so it asks first.
        if (enabled) automaticDisclosure = true else changeAutomaticRefresh(false)
    }

    fun saveGeneral(updated: GeneralPreferences) {
        val previous = generalPreferences
        generalPreferences = updated
        execute("Settings saved") {
            if (updated.landing != previous.landing) app.settings.setLandingPage(updated.landing)
            if (updated.secondaryCurrency != previous.secondaryCurrency) app.settings.setSecondaryCurrency(updated.secondaryCurrency)
            if (updated.showPortfolioChart != previous.showPortfolioChart) app.settings.setShowPortfolioChart(updated.showPortfolioChart)
            if (updated.zeroBasisRewards != previous.zeroBasisRewards) app.settings.setZeroBasisRewards(updated.zeroBasisRewards)
            if (updated.deviceName != previous.deviceName) app.settings.setDeviceName(updated.deviceName)
            if (updated.hideAmounts != previous.hideAmounts) app.settings.setHideAmounts(updated.hideAmounts)
            if (updated.candleCharts != previous.candleCharts) app.settings.setCandleCharts(updated.candleCharts)
        }
        // A new secondary currency needs its own Bitcoin price; fetch it now when automatic prices are allowed.
        if (updated.secondaryCurrency != null && updated.secondaryCurrency != previous.secondaryCurrency && preferences.automaticRefresh) {
            refreshPrices()
        }
    }

    fun saveAssetPreference(preference: AssetListPreference) =
        execute("Asset display saved") {
            app.settings.setAssetList(preference)
            assetPreference = preference
        }
    LaunchedEffect(presenter, snackbar) {
        presenter.messages.collect { snackbar.showSnackbar(it) }
    }
    if (marketDisclosure) {
        AlertDialog(
            onDismissRequest = { marketDisclosure = false },
            title = { Text("Load market data from CoinGecko?") },
            text = {
                Text(
                    "Main shows the top 250 coins by market cap, trending coins and daily movers from CoinGecko, with their " +
                        "logos. CoinGecko receives your display currency and IP address; no wallet data is sent. " +
                        "US dollar prices of coins on EVM chains come from DefiLlama, which receives those coins and " +
                        "your IP address. " +
                        "Without an API key, CoinGecko allows a few requests per minute; you can add a free key in " +
                        "Settings › API Keys.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        marketDisclosure = false
                        priceScope.launch {
                            withContext(host.dispatcher) { app.market.setConsent(true) }
                            refreshMarkets()
                        }
                    },
                    modifier = Modifier.testTag("market-consent-confirm"),
                ) { Text("Load") }
            },
            dismissButton = { TextButton(onClick = { marketDisclosure = false }) { Text("Cancel") } },
        )
    }
    if (automaticDisclosure) {
        AlertDialog(
            onDismissRequest = { automaticDisclosure = false },
            title = { Text("Turn on automatic refresh?") },
            text = {
                Text(
                    "Automatic refresh updates Bitcoin prices, market lists and wallet history on a schedule. Kraken receives " +
                        "Bitcoin pairs for your portfolio currencies and your IP address; CoinGecko receives your display " +
                        "currency and IP address; wallet addresses are sent to neither. Your Bitcoin provider receives watched " +
                        "addresses when wallet history refreshes, and only if you have allowed it. Turning this off withdraws " +
                        "automatic price consent.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        automaticDisclosure = false
                        changeAutomaticRefresh(true)
                    },
                    modifier = Modifier.testTag("automatic-refresh-confirm"),
                ) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { automaticDisclosure = false }) { Text("Cancel") } },
        )
    }
    if (dialog == "add") {
        SupportedSourceDialog(
            onSelect = { choice ->
                manualAsset = null
                dialog = choice.workflow.productDialog().key
            },
            onImportCsv = {
                dialog = null
                csvImport = true
            },
            onEvm = {
                dialog = null
                modelEvmConnect = true
            },
            onExchange = { kind ->
                dialog = null
                exchangeConnect = kind.id
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
                        activityStatus = saved[4].takeIf(String::isNotEmpty)?.let(io.valroot.core.ledger.ActivityStatus::valueOf),
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
    val pageData =
        snapshot?.takeIf {
            page !in setOf(Page.OVERVIEW, Page.REPORTS) || snapshotFilter.portfolio == selectedPortfolio
        }
    val modelMode = !advancedMode && page in setOf(Page.HOME, Page.OVERVIEW, Page.REPORTS, Page.ACCOUNTS, Page.SETTINGS)
    val canGoBack = advancedMode || selectedAsset != null || page == Page.ACCOUNTS || page == Page.SETTINGS
    val modalOpen =
        dialog != null ||
            modelAdd ||
            connectChooser ||
            modelConnect ||
            modelEvmConnect ||
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
    CompositionLocalProvider(LocalAmountsHidden provides generalPreferences.hideAmounts) {
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
                ModelLayout(
                    workspaceState = modelWorkspaceState,
                    page = page,
                    setPage = { page = it },
                    selectedPortfolio = selectedPortfolio,
                    setSelectedPortfolio = { selectedPortfolio = it },
                    selectedAsset = selectedAsset,
                    setSelectedAsset = { selectedAsset = it },
                    period = period,
                    setPeriod = { period = it },
                    setSelectedActivity = { selectedActivity = it },
                    setDialog = { dialog = it },
                    message = message,
                    setMessage = { message = it },
                    preferences = preferences,
                    updatePreferences = { change -> change(preferences).also { preferences = it } },
                    setModelManual = { modelManual = it },
                    setModelAdd = { modelAdd = it },
                    setModelEditingId = { modelEditingId = it },
                    setModelHistory = { modelHistory = it },
                    setConnectChooser = { connectChooser = it },
                    setManualAsset = { manualAsset = it },
                    lockDelay = lockDelay,
                    setLockDelay = { lockDelay = it },
                    hasCoingeckoKey = hasCoingeckoKey,
                    setHasCoingeckoKey = { hasCoingeckoKey = it },
                    hasAlchemyKey = hasAlchemyKey,
                    setHasAlchemyKey = { hasAlchemyKey = it },
                    hasOneInchKey = hasOneInchKey,
                    setHasOneInchKey = { hasOneInchKey = it },
                    biometric = biometric,
                    setBiometric = { biometric = it },
                    marketFavorites = marketFavorites,
                    toggleMarketFavorite = { asset ->
                        (if (asset in marketFavorites) marketFavorites - asset else marketFavorites + asset).also { marketFavorites = it }
                    },
                    setAdvancedMode = { advancedMode = it },
                    setAdvancedOrigin = { advancedOrigin = it },
                    modelReturnPage = modelReturnPage,
                    setModelReturnPage = { modelReturnPage = it },
                    chart = chart,
                    revision = revision,
                    onRevision = { revision++ },
                    app = app,
                    host = host,
                    presenter = presenter,
                    dashboardRevision = dashboardRevision,
                    snapshot = snapshot,
                    snapshotFilter = snapshotFilter,
                    appearance = appearance,
                    assetPreference = assetPreference,
                    generalPreferences = generalPreferences,
                    pricePhase = pricePhase,
                    marketRefreshing = marketRefreshing,
                    syncStatus = syncStatus,
                    snackbar = snackbar,
                    execute = { success, action -> execute(success, action) },
                    saveGeneral = ::saveGeneral,
                    onAssetPreference = ::saveAssetPreference,
                    refreshMarkets = { maxAgeMillis, quiet -> refreshMarkets(maxAgeMillis, quiet) },
                    refreshPrices = { refreshPrices(it) },
                    showPriceRefreshDetails = ::showPriceRefreshDetails,
                    onAutomaticSwitch = ::onAutomaticSwitch,
                    onAppearance = onAppearance,
                    onSetup = onSetup,
                )
            } else {
                ClassicLayout(
                    page = page,
                    setPage = { page = it },
                    selectedPortfolio = selectedPortfolio,
                    setSelectedAsset = { selectedAsset = it },
                    period = period,
                    search = search,
                    kind = kind,
                    offset = offset,
                    activityStatus = activityStatus,
                    activityNetwork = activityNetwork,
                    selectedAccount = selectedAccount,
                    setDialog = { dialog = it },
                    message = message,
                    preferences = preferences,
                    advancedMode = advancedMode,
                    menuOpen = menuOpen,
                    setMenuOpen = { menuOpen = it },
                    setSnackbarHeight = { snackbarHeight = it },
                    chart = chart,
                    revision = revision,
                    app = app,
                    host = host,
                    presenter = presenter,
                    data = pageData,
                    snapshotFilter = snapshotFilter,
                    compact = compact,
                    wide = wide,
                    homeScroll = homeScroll,
                    moreScroll = moreScroll,
                    overviewScroll = overviewScroll,
                    activityScroll = activityScroll,
                    accountScroll = accountScroll,
                    reportScroll = reportScroll,
                    settingsScroll = settingsScroll,
                    syncStatus = syncStatus,
                    pricePhase = pricePhase,
                    busy = busy,
                    snackbar = snackbar,
                    snackbarInset = snackbarInset,
                    appearance = appearance,
                    assetPreference = assetPreference,
                    onAssetPreference = ::saveAssetPreference,
                    onAppearance = onAppearance,
                    onAutomaticSwitch = ::onAutomaticSwitch,
                    execute = { success, action -> execute(success, action) },
                    refreshPrices = { refreshPrices(it) },
                    showPriceRefreshDetails = ::showPriceRefreshDetails,
                    onReturnFromAdvanced = ::returnFromAdvanced,
                    onSetup = onSetup,
                    setupError = setupError,
                    hostRevision = hostRevision,
                    onCurrency = { currency -> execute { app.assets.setCurrency(currency) } },
                    setSelectedPortfolio = { selectedPortfolio = it },
                    setPeriod = { period = it },
                    setMessage = { message = it },
                    onRevision = { revision++ },
                    setSearch = { search = it },
                    setKind = { kind = it },
                    updateOffset = { change -> offset = change(offset) },
                    setActivityStatus = { activityStatus = it },
                    setActivityNetwork = { activityNetwork = it },
                    setSelectedAccount = { selectedAccount = it },
                    setSelectedActivity = { selectedActivity = it },
                    setRemoveAccount = { removeAccount = it },
                    setAddressWallet = { addressWallet = it },
                    setSourceScanTarget = { sourceScanTarget = it },
                    updatePreferences = { change -> change(preferences).also { preferences = it } },
                )
            }
            ProductDialogs(
                page = page,
                selectedPortfolio = selectedPortfolio,
                setSelectedPortfolio = { selectedPortfolio = it },
                selectedAsset = selectedAsset,
                setSelectedAsset = { selectedAsset = it },
                kind = kind,
                setSelectedAccount = { selectedAccount = it },
                selectedActivity = selectedActivity,
                setSelectedActivity = { selectedActivity = it },
                dialog = dialog,
                setDialog = { dialog = it },
                setMessage = { message = it },
                removeAccount = removeAccount,
                setRemoveAccount = { removeAccount = it },
                addressWallet = addressWallet,
                setAddressWallet = { addressWallet = it },
                sourceScanTarget = sourceScanTarget,
                setSourceScanTarget = { sourceScanTarget = it },
                modelManual = modelManual,
                setModelManual = { modelManual = it },
                modelAdd = modelAdd,
                setModelAdd = { modelAdd = it },
                modelEditingId = modelEditingId,
                setModelEditingId = { modelEditingId = it },
                modelEditingDraft = modelEditingDraft,
                setModelEditingDraft = { modelEditingDraft = it },
                modelHistory = modelHistory,
                setModelHistory = { modelHistory = it },
                connectChooser = connectChooser,
                setConnectChooser = { connectChooser = it },
                modelConnect = modelConnect,
                setModelConnect = { modelConnect = it },
                modelEvmConnect = modelEvmConnect,
                setModelEvmConnect = { modelEvmConnect = it },
                exchangeConnect = exchangeConnect,
                setExchangeConnect = { exchangeConnect = it },
                csvImport = csvImport,
                setCsvImport = { csvImport = it },
                showRefreshDetails = showRefreshDetails,
                setShowRefreshDetails = { showRefreshDetails = it },
                manualAsset = manualAsset,
                setManualAsset = { manualAsset = it },
                sourceDraftEndpoint = sourceDraftEndpoint,
                setSourceDraftEndpoint = { sourceDraftEndpoint = it },
                sourceDraftConsent = sourceDraftConsent,
                setSourceDraftConsent = { sourceDraftConsent = it },
                refreshDetails = refreshDetails,
                selectedManualDraft = selectedManualDraft,
                selectedManualCanEdit = selectedManualCanEdit,
                revision = revision,
                onRevision = { revision++ },
                app = app,
                host = host,
                presenter = presenter,
                snapshot = snapshot,
                syncStatus = syncStatus,
                busy = busy,
                modelMode = modelMode,
                modelDraftState = modelDraftState,
                modelDraftScope = modelDraftScope,
                sourceFlowState = sourceFlowState,
                execute = { success, action -> execute(success, action) },
                refreshPrices = { refreshPrices(it) },
            )
        }
    }
}
