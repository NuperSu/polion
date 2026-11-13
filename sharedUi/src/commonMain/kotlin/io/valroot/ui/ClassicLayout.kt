package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.reconcile.EffectiveActivity

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ClassicLayout(
    page: Page,
    setPage: (Page) -> Unit,
    selectedPortfolio: PortfolioId?,
    setSelectedAsset: (AssetValuation?) -> Unit,
    period: Int,
    search: String,
    kind: ActivityKind?,
    offset: Int,
    activityStatus: ActivityStatus?,
    activityNetwork: String?,
    selectedAccount: AccountId?,
    setDialog: (String?) -> Unit,
    message: String?,
    preferences: RefreshPreferences,
    advancedMode: Boolean,
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
    setSnackbarHeight: (Int) -> Unit,
    chart: List<HistoryPoint>,
    revision: Int,
    app: PortfolioApplication,
    host: PlatformActions,
    presenter: ProductPresenter,
    data: PortfolioSnapshot?,
    snapshotFilter: ActivityFilter,
    compact: Boolean,
    wide: Boolean,
    homeScroll: LazyListState,
    moreScroll: LazyListState,
    overviewScroll: LazyListState,
    activityScroll: LazyListState,
    accountScroll: LazyListState,
    reportScroll: LazyListState,
    settingsScroll: LazyListState,
    syncStatus: SyncStatus,
    pricePhase: PriceRefreshPhase,
    busy: Boolean,
    snackbar: SnackbarHostState,
    snackbarInset: Dp,
    appearance: AppearanceTheme,
    assetPreference: AssetListPreference,
    onAssetPreference: (AssetListPreference) -> Unit,
    onAppearance: (AppearanceTheme) -> Unit,
    onAutomaticSwitch: (Boolean) -> Unit,
    execute: (String, () -> Unit) -> Unit,
    refreshPrices: (Boolean) -> Unit,
    showPriceRefreshDetails: (String) -> Unit,
    onReturnFromAdvanced: () -> Unit,
    onSetup: (Boolean) -> Unit,
    setupError: String?,
    hostRevision: Long,
    onCurrency: (AssetId) -> Unit,
    setSelectedPortfolio: (PortfolioId?) -> Unit,
    setPeriod: (Int) -> Unit,
    setMessage: (String?) -> Unit,
    onRevision: () -> Unit,
    setSearch: (String) -> Unit,
    setKind: (ActivityKind?) -> Unit,
    updateOffset: ((Int) -> Int) -> Unit,
    setActivityStatus: (ActivityStatus?) -> Unit,
    setActivityNetwork: (String?) -> Unit,
    setSelectedAccount: (AccountId?) -> Unit,
    setSelectedActivity: (EffectiveActivity?) -> Unit,
    setRemoveAccount: (Account?) -> Unit,
    setAddressWallet: (TrackingTarget?) -> Unit,
    setSourceScanTarget: (TrackingTarget?) -> Unit,
    updatePreferences: ((RefreshPreferences) -> RefreshPreferences) -> RefreshPreferences,
) {
    Row(Modifier.fillMaxSize()) {
        if (!compact) {
            WorkspaceRail(page, { setPage(it) }, wide, { setDialog("add") })
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = Paper,
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    Modifier.testTag("status-message").onSizeChanged { setSnackbarHeight(it.height) },
                )
            },
            topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (advancedMode) {
                        TextButton(onClick = onReturnFromAdvanced) { Text("Back") }
                    } else if (page !in compactDestinations) {
                        TextButton(
                            onClick = { setPage(Page.OVERVIEW) },
                            modifier =
                                Modifier.testTag("screen-back").semantics {
                                    contentDescription =
                                        "Back to portfolio"
                                },
                        ) { Text("‹") }
                    }
                    Text(page.label, modifier = Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = { refreshPrices(true) },
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
                            onClick = { setMenuOpen(true) },
                            modifier =
                                Modifier.testTag("workspace-menu").semantics {
                                    contentDescription =
                                        "Open menu"
                                },
                        ) {
                            RevisedIcon("menu", Modifier.size(23.dp))
                        }
                        DropdownMenu(menuOpen, { setMenuOpen(false) }) {
                            listOf(Page.ACCOUNTS, Page.SETTINGS, Page.MORE).forEach { destination ->
                                DropdownMenuItem(text = { Text(destination.label) }, onClick = {
                                    setMenuOpen(false)
                                    setPage(destination)
                                })
                            }
                            DropdownMenuItem(text = { Text("Refresh details") }, onClick = {
                                setMenuOpen(false)
                                showPriceRefreshDetails("Pull down to refresh saved prices across portfolios.")
                            })
                        }
                    }
                }
            },
            floatingActionButton = {
                if (page == Page.OVERVIEW) {
                    FloatingActionButton(
                        onClick = { setDialog("add") },
                        modifier = Modifier.testTag("add-action").semantics { contentDescription = "Add to portfolio" },
                    ) { Text("+", fontSize = 28.sp) }
                }
            },
            bottomBar = {
                if (compact) {
                    CompactNavigation(page, { setPage(it) }, { setDialog("add") })
                }
            },
        ) { insets ->
            val activityAccounts =
                remember(data, snapshotFilter) {
                    data?.let { activityAccountScope(it.ownership, snapshotFilter) }.orEmpty()
                }
            PullToRefreshBox(
                isRefreshing = pricePhase == PriceRefreshPhase.RUNNING,
                onRefresh = { refreshPrices(true) },
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
                            horizontal = if (compact) ValrootSpacing.compactGutter else ValrootSpacing.wideGutter,
                            vertical = if (compact) 20.dp else 28.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (page == Page.ACCOUNTS) item { SyncBanner(syncStatus, data?.sourceStatus, { setDialog("sync") }) }
                    if (data?.sampleData == true) item { StatusPill("EXAMPLE DATA · stored only on this device", false) }
                    if (data == null) {
                        item { EmptyCard("Opening your portfolio", "Your local data stays on this device.") }
                    } else {
                        when (page) {
                            Page.HOME -> {
                                item {
                                    RevisedMain(app, host, data) { coin ->
                                        setSelectedAsset(
                                            data.valuations.find { it.asset == coin.asset } ?: coin.detailValuation(),
                                        )
                                    }
                                }
                            }
                            Page.MORE -> {
                                item {
                                    Panel {
                                        Text("Advanced tools", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                        val destinations = listOf(Page.ACCOUNTS, Page.ACTIVITY, Page.REPORTS, Page.SETTINGS)
                                        destinations.forEach { destination ->
                                            OutlinedButton(
                                                onClick = { setPage(destination) },
                                                modifier = Modifier.fillMaxWidth().testTag("more-${destination.name}"),
                                            ) {
                                                Text(destination.label)
                                            }
                                        }
                                    }
                                }
                                item { PrivacyFootnote() }
                            }
                            Page.OVERVIEW ->
                                classicOverviewItems(
                                    setPage = setPage,
                                    selectedPortfolio = selectedPortfolio,
                                    setSelectedPortfolio = setSelectedPortfolio,
                                    setSelectedAsset = setSelectedAsset,
                                    period = period,
                                    setPeriod = setPeriod,
                                    setDialog = setDialog,
                                    chart = chart,
                                    app = app,
                                    data = data,
                                    compact = compact,
                                    wide = wide,
                                    presenter = presenter,
                                    assetPreference = assetPreference,
                                    busy = busy,
                                    execute = execute,
                                    onAssetPreference = onAssetPreference,
                                )
                            Page.ACTIVITY ->
                                classicActivityItems(
                                    search = search,
                                    setSearch = setSearch,
                                    kind = kind,
                                    setKind = setKind,
                                    offset = offset,
                                    updateOffset = updateOffset,
                                    activityStatus = activityStatus,
                                    setActivityStatus = setActivityStatus,
                                    activityNetwork = activityNetwork,
                                    setActivityNetwork = setActivityNetwork,
                                    selectedAccount = selectedAccount,
                                    setSelectedAccount = setSelectedAccount,
                                    setSelectedActivity = setSelectedActivity,
                                    setDialog = setDialog,
                                    data = data,
                                    compact = compact,
                                    presenter = presenter,
                                    snapshotFilter = snapshotFilter,
                                    activityAccounts = activityAccounts,
                                )
                            Page.ACCOUNTS ->
                                classicAccountsItems(
                                    setDialog = setDialog,
                                    setMessage = setMessage,
                                    setRemoveAccount = setRemoveAccount,
                                    setAddressWallet = setAddressWallet,
                                    setSourceScanTarget = setSourceScanTarget,
                                    onRevision = onRevision,
                                    app = app,
                                    host = host,
                                    data = data,
                                    busy = busy,
                                    syncStatus = syncStatus,
                                    execute = execute,
                                )
                            Page.REPORTS ->
                                classicReportsItems(
                                    selectedPortfolio = selectedPortfolio,
                                    setSelectedPortfolio = setSelectedPortfolio,
                                    setSelectedAsset = setSelectedAsset,
                                    period = period,
                                    setPeriod = setPeriod,
                                    setDialog = setDialog,
                                    setMessage = setMessage,
                                    chart = chart,
                                    revision = revision,
                                    onRevision = onRevision,
                                    app = app,
                                    host = host,
                                    data = data,
                                    compact = compact,
                                    presenter = presenter,
                                    hostRevision = hostRevision,
                                    execute = execute,
                                )
                            Page.SETTINGS ->
                                classicSettingsItems(
                                    setDialog = setDialog,
                                    setMessage = setMessage,
                                    preferences = preferences,
                                    updatePreferences = updatePreferences,
                                    onRevision = onRevision,
                                    app = app,
                                    host = host,
                                    data = data,
                                    appearance = appearance,
                                    onAppearance = onAppearance,
                                    execute = execute,
                                    onAutomaticSwitch = onAutomaticSwitch,
                                    onCurrency = onCurrency,
                                    onSetup = onSetup,
                                    setupError = setupError,
                                )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}
