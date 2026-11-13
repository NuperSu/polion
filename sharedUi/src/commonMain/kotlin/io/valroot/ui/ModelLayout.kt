package io.valroot.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.GeneralPreferences
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.reconcile.EffectiveActivity
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ModelLayout(
    workspaceState: SaveableStateHolder,
    page: Page,
    setPage: (Page) -> Unit,
    selectedPortfolio: PortfolioId?,
    setSelectedPortfolio: (PortfolioId?) -> Unit,
    selectedAsset: AssetValuation?,
    setSelectedAsset: (AssetValuation?) -> Unit,
    period: Int,
    setPeriod: (Int) -> Unit,
    setSelectedActivity: (EffectiveActivity?) -> Unit,
    setDialog: (String?) -> Unit,
    message: String?,
    setMessage: (String?) -> Unit,
    preferences: RefreshPreferences,
    /** Applies a change to the current preferences and returns them. */
    updatePreferences: ((RefreshPreferences) -> RefreshPreferences) -> RefreshPreferences,
    setModelManual: (Boolean) -> Unit,
    setModelAdd: (Boolean) -> Unit,
    setModelEditingId: (String?) -> Unit,
    setModelHistory: (Boolean) -> Unit,
    setConnectChooser: (Boolean) -> Unit,
    setManualAsset: (AssetId?) -> Unit,
    lockDelay: Long,
    setLockDelay: (Long) -> Unit,
    hasCoingeckoKey: Boolean,
    setHasCoingeckoKey: (Boolean) -> Unit,
    hasAlchemyKey: Boolean,
    setHasAlchemyKey: (Boolean) -> Unit,
    hasOneInchKey: Boolean,
    setHasOneInchKey: (Boolean) -> Unit,
    biometric: BiometricUnlock,
    setBiometric: (BiometricUnlock) -> Unit,
    marketFavorites: Set<AssetId>,
    /** Adds or removes a favourite from the current set and returns the new set. */
    toggleMarketFavorite: (AssetId) -> Set<AssetId>,
    setAdvancedMode: (Boolean) -> Unit,
    setAdvancedOrigin: (List<String>) -> Unit,
    modelReturnPage: String,
    setModelReturnPage: (String) -> Unit,
    chart: List<HistoryPoint>,
    revision: Int,
    onRevision: () -> Unit,
    app: PortfolioApplication,
    host: PlatformActions,
    presenter: ProductPresenter,
    dashboardRevision: Long,
    snapshot: PortfolioSnapshot?,
    snapshotFilter: ActivityFilter,
    appearance: AppearanceTheme,
    assetPreference: AssetListPreference,
    generalPreferences: GeneralPreferences,
    pricePhase: PriceRefreshPhase,
    marketRefreshing: Boolean,
    syncStatus: SyncStatus,
    snackbar: SnackbarHostState,
    execute: (String, () -> Unit) -> Unit,
    saveGeneral: (GeneralPreferences) -> Unit,
    onAssetPreference: (AssetListPreference) -> Unit,
    refreshMarkets: (Long, Boolean) -> Unit,
    refreshPrices: (Boolean) -> Unit,
    showPriceRefreshDetails: (String) -> Unit,
    onAutomaticSwitch: (Boolean) -> Unit,
    onAppearance: (AppearanceTheme) -> Unit,
    onSetup: (Boolean) -> Unit,
) {
    workspaceState.SaveableStateProvider("workspace") {
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
            showPortfolioChart = generalPreferences.showPortfolioChart,
            refreshing = pricePhase == PriceRefreshPhase.RUNNING || marketRefreshing,
            refreshQueued = pricePhase == PriceRefreshPhase.QUEUED,
            syncStatus = syncStatus,
            favorites = marketFavorites,
            onFavorite = { asset ->
                val favorites = toggleMarketFavorite(asset)
                execute("Favorites saved") { app.market.setFavorites(favorites) }
            },
            onPage = {
                if (it == Page.ACCOUNTS || it == Page.SETTINGS) setModelReturnPage(page.name)
                setPage(it)
            },
            onBackAux = { setPage(Page.valueOf(modelReturnPage)) },
            onPortfolio = { setSelectedPortfolio(it) },
            onAsset = { setSelectedAsset(it) },
            onPeriod = { setPeriod(it) },
            onAssetPreference = onAssetPreference,
            onCurrency = { currency ->
                val portfolio = snapshot?.ownership?.portfolios?.find { it.id == selectedPortfolio }
                execute("Display currency saved") {
                    if (portfolio == null) {
                        app.assets.setCurrency(currency)
                    } else {
                        app.accounts.savePortfolio(portfolio.copy(valuationCurrency = currency.value))
                    }
                }
            },
            // On Main, refresh updates market lists and, when allowed, prices; the load card asks for market consent.
            onRefresh = {
                if (page == Page.HOME && selectedAsset == null) {
                    refreshMarkets(60_000L, true)
                    refreshPrices(false)
                } else {
                    refreshPrices(true)
                }
            },
            onMarketRefresh = { age -> refreshMarkets(age, age > 60_000L) },
            onToggleAmounts = { saveGeneral(generalPreferences.copy(hideAmounts = !generalPreferences.hideAmounts)) },
            chartStyle =
                if (generalPreferences.candleCharts) {
                    io.valroot.ui.model.ChartStyle.CANDLES
                } else {
                    io.valroot.ui.model.ChartStyle.LINE
                },
            onChartStyle = { style ->
                saveGeneral(
                    generalPreferences.copy(
                        candleCharts =
                            style == io.valroot.ui.model.ChartStyle.CANDLES,
                    ),
                )
            },
            onAdd = {
                setModelEditingId(null)
                setModelManual(false)
                setModelAdd(true)
            },
            onAdvanced = { destination ->
                val current = presenter.state
                setAdvancedOrigin(
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
                    ),
                )
                if (current.page == Page.HOME && current.detailAsset != null) setSelectedPortfolio(null)
                if (destination == Page.ACTIVITY && selectedAsset != null) {
                    presenter.dispatch(ProductEvent.AssetActivity(selectedAsset.asset))
                } else {
                    setPage(destination)
                }
                setAdvancedMode(true)
            },
            onRefreshDetails = { showPriceRefreshDetails("Price refresh has not run.") },
            onSyncDetails = { setModelHistory(true) },
            onConnectBitcoin = { setConnectChooser(true) },
            onAccountChanged = { execute("Account updated; saved history retained") {} },
            onAssetChanged = { message -> execute(message) {} },
            onAccountTool = { tool ->
                setDialog(
                    when (tool) {
                        AccountTool.RULES -> "rule"
                        AccountTool.RECONCILIATION -> "reconcile"
                        AccountTool.UTXO -> "utxo"
                    },
                )
            },
            onAutomatic = { enabled -> onAutomaticSwitch(enabled) },
            onWifi = { enabled ->
                val updated = updatePreferences { it.copy(wifiOnly = enabled) }
                execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
            },
            onTransaction = { setSelectedActivity(it) },
            statusContent = { SnackbarHost(snackbar, Modifier.testTag("status-message")) },
            settingsContent = { openAdvanced ->
                ProductSettings(
                    snapshot,
                    preferences,
                    host.wifiOnlyRefreshSupported,
                    appearance,
                    assetPreference,
                    host.hasLock,
                    openAdvanced,
                    SettingsActions(
                        onAutomatic = { onAutomaticSwitch(it) },
                        onWifi = { enabled ->
                            val updated = updatePreferences { it.copy(wifiOnly = enabled) }
                            execute("Refresh settings saved") { host.updateRefreshPreferences(updated) }
                        },
                        onCurrency = { code -> execute("Currency saved") { app.assets.setCurrency(AssetId(code)) } },
                        onAppearance = { selected ->
                            execute("Appearance saved") {
                                app.settings.setTheme(selected.key)
                                onAppearance(selected)
                            }
                        },
                        onAssetPreference = onAssetPreference,
                        onDialog = { setDialog(it) },
                        onExportCsv = {
                            execute("Export ready") {
                                host.saveFile("valroot-activities.csv", app.csv.export().encodeToByteArray()) { setMessage(it) }
                            }
                        },
                        onConnect = { setConnectChooser(true) },
                        onLockNow = host::lock,
                        onRemoveLock = {
                            host.removeLockPassword {
                                setMessage(it)
                                onRevision()
                            }
                        },
                        onSetup = onSetup,
                        onRemoveExample = { execute("Example data removed") { app.demo.removeExampleData() } },
                        onGeneral = saveGeneral,
                        onLockDelay = { millis ->
                            setLockDelay(millis)
                            host.setLockDelay(millis)
                        },
                        onCoingeckoKey = { key ->
                            setHasCoingeckoKey(key != null)
                            execute(
                                if (key ==
                                    null
                                ) {
                                    "CoinGecko key removed"
                                } else {
                                    "CoinGecko key saved"
                                },
                            ) { app.market.setCoingeckoApiKey(key) }
                        },
                        onAlchemyKey = { key ->
                            val problem = withContext(host.dispatcher) { saveAlchemyKey(app, key) }
                            if (problem == null) {
                                setHasAlchemyKey(key != null)
                                presenter.notify(if (key == null) "Alchemy key removed" else "Alchemy key saved")
                            }
                            problem
                        },
                        onOneInchKey = { key ->
                            val problem = withContext(host.dispatcher) { saveOneInchKey(app, key) }
                            if (problem == null) {
                                setHasOneInchKey(key != null)
                                presenter.notify(if (key == null) "1inch key removed" else "1inch key saved")
                            }
                            problem
                        },
                        onBiometric = { enabled ->
                            host.setBiometricUnlock(enabled) { result ->
                                setBiometric(host.biometricUnlock())
                                presenter.notify(result)
                            }
                        },
                    ),
                    general = generalPreferences,
                    device =
                        SettingsDevice(
                            host.defaultDeviceName,
                            biometric,
                            hasCoingeckoKey,
                            lockDelay,
                            hasAlchemyKey = hasAlchemyKey,
                            hasOneInchKey = hasOneInchKey,
                        ),
                    notifications = { NotificationSettingsSection(app, host) },
                    csvImports = { CsvImportHistorySection(app, host, onChanged = { onRevision() }) },
                ) {
                    snapshot?.let { data ->
                        AccountsWorkspace(
                            app,
                            host,
                            data,
                            selectedPortfolio,
                            dashboardRevision + revision,
                            syncStatus,
                            onConnect = { setConnectChooser(true) },
                            onHistory = { setModelHistory(true) },
                            onAdvanced = openAdvanced,
                            onChanged = { execute("Account updated; saved history retained") {} },
                            onTool = { tool ->
                                setDialog(
                                    when (tool) {
                                        AccountTool.RULES -> "rule"
                                        AccountTool.RECONCILIATION -> "reconcile"
                                        AccountTool.UTXO -> "utxo"
                                    },
                                )
                            },
                            compact = true,
                            onSync = { host.refresh { presenter.notify(it) } },
                        )
                    }
                }
            },
            onManual = { asset ->
                setModelEditingId(null)
                setManualAsset(asset?.asset)
                setModelManual(true)
                setModelAdd(true)
            },
        )
    }
}
