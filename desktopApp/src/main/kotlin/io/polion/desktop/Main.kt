package io.polion.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.polion.core.ApplicationInfo
import io.polion.core.alerts.NotificationMessage
import io.polion.core.application.BitcoinConnector
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.SourceConnector
import io.polion.core.application.SourceRefreshOptions
import io.polion.core.application.SourceRegistry
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.evm.EVM_MANIFEST
import io.polion.core.evm.EvmConnector
import io.polion.core.model.TrackingTargetId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.sync.JobState
import io.polion.ui.MARKET_SCHEDULED_AGE_MILLIS
import io.polion.ui.OrderBookResult
import io.polion.ui.PRICE_REFRESH_INTERVAL_MILLIS
import io.polion.ui.PlatformActions
import io.polion.ui.PolionApp
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import io.polion.ui.UnlockResult
import io.polion.ui.WALLET_REFRESH_INTERVAL_MILLIS
import io.polion.ui.cooldownDelayMillis
import io.polion.ui.loadOrderBook
import io.polion.ui.marketRefreshMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

internal fun discoverWalletStatus(
    application: PortfolioApplication,
    walletId: TrackingTargetId,
    transport: EsploraTransport,
    providerEndpoint: String = application.sourceEndpoint(),
    onProgress: (String) -> Unit,
): SyncStatus =
    runCatching {
        var queries = 0
        val discoveryTransport =
            EsploraTransport { path ->
                transport.get(path).also {
                    queries++
                    onProgress("Discovering wallet · address queries answered: $queries")
                }
            }
        val result = application.discoverBitcoinWallet(walletId, discoveryTransport, providerEndpoint = providerEndpoint)
        val states =
            application.refreshBitcoin(transport, providerEndpoint = providerEndpoint) { progress ->
                onProgress(
                    when (progress.phase) {
                        "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                        "verify" -> "Checking chain consistency…"
                        "apply" -> "Applying verified history…"
                        else ->
                            "Address ${progress.addressIndex}/${progress.totalAddresses} · " +
                                "${progress.pages} pages · ${progress.transactions} transactions"
                    },
                )
            }
        if (states.isEmpty() || states.any { it != io.polion.core.sync.JobState.COMPLETED }) {
            SyncStatus(SyncPhase.ERROR, application.sourceFailureMessage() ?: "Wallet history is incomplete; retry.")
        } else {
            SyncStatus(
                SyncPhase.SUCCESS,
                listOfNotNull(
                    "Wallet discovery complete: ${result.addresses} addresses checked, ${result.usedAddresses} used; " +
                        "coverage remains unverified.",
                    result.notice,
                ).joinToString(" "),
            )
        }
    }.getOrElse {
        SyncStatus(
            SyncPhase.ERROR,
            (it as? BitcoinProviderException)?.reason?.userMessage
                ?: (it as? io.polion.core.application.BitcoinDiscoveryConflict)?.message
                ?: "Wallet discovery incomplete. Check consent, provider and discovery limits; retry.",
        )
    }

/** Switching windows is routine on desktop, so leaving locks after five minutes unless Settings says otherwise. */
const val DEFAULT_LOCK_DELAY_MILLIS = 300_000L

/** Notification checks start this long after launch while an alert or notification setting is on, then repeat. */
private const val NOTIFICATION_FIRST_CHECK_MILLIS = 30_000L
private const val NOTIFICATION_INTERVAL_MILLIS = 300_000L

private const val MARKET_PAUSED_MESSAGE = "Market data is paused while data is restored."

internal class DesktopHost(
    private val directory: Path,
    demo: Boolean,
    private val automaticPriceTransport: (
        () -> Unit,
    ) -> (String) -> String = { before -> KrakenPriceTransport(beforeRequest = before)::get },
) : PlatformActions {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val unlockExecutor = Executors.newSingleThreadExecutor()
    private val priceExecutor = Executors.newSingleThreadScheduledExecutor()
    private val manualPricePending = AtomicBoolean(false)
    override val priceRefreshStatus = MutableStateFlow(io.polion.ui.PriceRefreshPhase.IDLE)
    private var automaticPriceTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val priceGate = Any()

    @Volatile private var priceRestorePaused = false
    internal val priceReplacementInProgress: Boolean get() = priceRestorePaused
    private val syncQueue = BitcoinSyncQueue()
    private val priceTransport = DesktopCoingeckoTransport()
    override val dataRevision = MutableStateFlow(0L)
    override val syncStatus = MutableStateFlow(SyncStatus())
    override val hasLock: Boolean get() = lockStore.hasLock

    // Read once from the environment or /etc/hostname; a DNS lookup could block the UI thread.
    override val defaultDeviceName: String by lazy {
        (
            System.getenv("HOSTNAME")
                ?: runCatching {
                    java.nio.file.Files
                        .readString(
                            java.nio.file.Path
                                .of("/etc/hostname"),
                        )
                }.getOrNull()
        )?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: "This computer"
    }
    private val file = directory.resolve("portfolio.sqlite")
    private val verifierFile = directory.resolve("lock-verifier")
    private val lockStore = SecureLockStore(verifierFile)
    private val files = BackupFiles()
    private var db: JvmSqliteDatabase
    private var syncDb: JvmSqliteDatabase? = null
    private var syncApp: PortfolioApplication? = null
    val app: androidx.compose.runtime.MutableState<PortfolioApplication>
    val locked = mutableStateOf(lockStore.hasLock)

    @Volatile private var lockDelay = DEFAULT_LOCK_DELAY_MILLIS
    private var leftAt: Long? = null

    override val lockDelayMillis: Long get() = lockDelay

    override fun setLockDelay(millis: Long) {
        lockDelay = millis
        executor.execute { runCatching { app.value.setLockDelay(millis) } }
    }

    /** The window lost focus: lock now, or remember when, depending on the delay. */
    fun left() {
        if (lockDelay == 0L) lock() else leftAt = System.nanoTime() / 1_000_000L
    }

    /** The window regained focus: lock if it was away at least as long as the delay. */
    fun returned() {
        val away = leftAt?.let { System.nanoTime() / 1_000_000L - it }
        leftAt = null
        if (away != null && away >= lockDelay) lock()
    }

    private var refreshTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var notificationTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val hostLock = Any()
    private val closing = AtomicBoolean(false)
    private val refreshPreference = directory.resolve("background-refresh")

    init {
        Files.createDirectories(directory)
        runCatching {
            Files.setPosixFilePermissions(
                directory,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
        db = JvmSqliteDatabase(file.toString())
        app =
            try {
                mutableStateOf(PortfolioApplication(db, System::currentTimeMillis))
            } catch (failure: Exception) {
                runCatching { db.close() }
                throw failure
            }
        if (demo) app.value.seedDemo()
        lockDelay = app.value.lockDelayMillis() ?: DEFAULT_LOCK_DELAY_MILLIS
        if (runCatching { reconcileRefreshPreferences() }.isFailure) {
            runCatching { app.value.recordAutomaticPriceSchedulingFailure() }
        }
        reconcileNotificationChecks()
    }

    override fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            runCatching {
                files.save(
                    name,
                    bytes,
                )
            }.fold({ onResult(if (it) "File saved" else "Cancelled") }, { onResult("Could not save file") })
        }
    }

    override fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseImport() }.getOrNull()
            if (path == null) {
                onResult("", null)
                return@invokeLater
            }
            executor.execute {
                val text = runCatching { readText(Files.newInputStream(path), maxChars) }.getOrNull()
                SwingUtilities.invokeLater { onResult(path.fileName.toString(), text) }
            }
        }
    }

    override fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseBackupDestination() }.getOrNull()
            if (path == null) {
                onResult("Backup was not saved")
                return@invokeLater
            }
            executor.execute {
                val secret = password.toCharArray()
                // Written beside the destination and moved into place, so a failed export never leaves a partial backup.
                val temp = runCatching { Files.createTempFile(path.toAbsolutePath().parent, ".polion-backup-", ".tmp") }.getOrNull()
                val message =
                    if (temp == null) {
                        "Could not save backup"
                    } else {
                        try {
                            Files.newOutputStream(temp).buffered().use { out ->
                                app.value.exportBackup(secret) { bytes, offset, length -> out.write(bytes, offset, length) }
                            }
                            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                            "Backup saved"
                        } catch (_: Exception) {
                            "Could not save backup"
                        } finally {
                            secret.fill('\u0000')
                            runCatching { Files.deleteIfExists(temp) }
                        }
                    }
                SwingUtilities.invokeLater { onResult(message) }
            }
        }
    }

    override fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseBackup() }.getOrNull()
            if (path == null) onResult("No backup selected") else restoreBackup(path, password, onResult)
        }
    }

    internal fun restoreBackup(
        path: Path,
        password: String,
        onResult: (String) -> Unit,
    ) {
        executor.execute {
            var message = "Could not restore backup"
            val accepted =
                syncQueue.tryRunExclusive {
                    var staging: Path? = null
                    var result = "Could not restore backup"
                    var swapped = false
                    var reopened = false
                    var pricePaused = false
                    var replacementReady = false
                    try {
                        staging = Files.createTempFile(directory, "restore-", ".sqlite")
                        JvmSqliteDatabase(staging.toString()).use { stagingDb ->
                            Files.newInputStream(path).buffered().use { input ->
                                app.value.restoreToStaging(stagingDb, { b, o, l -> input.read(b, o, l) }, password.toCharArray())
                            }
                        }
                        java.nio.channels.FileChannel
                            .open(staging, java.nio.file.StandardOpenOption.WRITE)
                            .use { it.force(true) }
                        priceRestorePaused = true
                        pricePaused = true
                        app.value.invalidateAutomaticPriceRequests()
                        synchronized(priceGate) {
                            closeSyncConnection()
                            db.close()
                            try {
                                // Another connection still holding a write-ahead log would replay it into the restored file.
                                check(walIsEmpty(file)) { "Polion is still writing; retry the restore" }
                                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                swapped = true
                            } finally {
                                db = JvmSqliteDatabase(file.toString())
                                reopened = true
                            }
                            val replacement = PortfolioApplication(db, System::currentTimeMillis)
                            SwingUtilities.invokeAndWait {
                                app.value = replacement
                                dataRevision.update { it + 1 }
                            }
                            replacementReady = true
                        }
                        result = "Backup restored"
                    } catch (failure: Exception) {
                        // Validation messages (wrong password, damaged or unsupported backup) explain a rejected file.
                        if (!swapped && failure is IllegalArgumentException) result = failure.message ?: result
                        if (swapped) result = "Backup restored; reopen the app to load it"
                        if (reopened && !swapped) {
                            runCatching {
                                val replacement = PortfolioApplication(db, System::currentTimeMillis)
                                SwingUtilities.invokeAndWait {
                                    app.value = replacement
                                    dataRevision.update { it + 1 }
                                }
                            }
                        }
                    } finally {
                        if (pricePaused) {
                            priceRestorePaused = false
                            // Restored data can turn alerts on or off, so the check schedule follows it.
                            if (swapped && replacementReady) runCatching { reconcileNotificationChecks() }
                            if (swapped &&
                                replacementReady &&
                                runCatching { reconcileRefreshPreferences() }.isFailure
                            ) {
                                result = "Backup restored; automatic price scheduling could not be updated"
                            }
                        }
                        staging?.let { runCatching { Files.deleteIfExists(it) } }
                    }
                    message = result
                }
            if (!accepted) message = "Wait for the active sync to finish, then restore"
            SwingUtilities.invokeLater { onResult(message) }
        }
    }

    /**
     * Runs one network task on the sync worker with its own database connection, so UI reads, saves and unlock
     * stay on the UI queue. A task is refused while another sync is active.
     */
    internal fun runSync(
        start: SyncStatus,
        onResult: (String) -> Unit,
        task: (PortfolioApplication) -> SyncStatus,
    ) {
        var status = SyncStatus(SyncPhase.ERROR, "Could not finish sync. Check storage and provider, then retry.")
        val accepted =
            syncQueue.tryStart(onReleased = {
                syncStatus.value = status
                dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(status.message) }
            }) {
                syncStatus.value = start
                status =
                    try {
                        task(syncApplication()).copy(operation = start.operation)
                    } catch (failure: Exception) {
                        SyncStatus(
                            SyncPhase.ERROR,
                            (failure as? BitcoinProviderException)?.reason?.userMessage
                                ?: "Could not finish sync. Check storage and provider, then retry.",
                        ).copy(operation = start.operation)
                    }
            }
        if (!accepted) SwingUtilities.invokeLater { onResult("A sync is already running") }
    }

    private fun syncApplication(): PortfolioApplication =
        synchronized(hostLock) {
            syncApp ?: run {
                val connection = JvmSqliteDatabase(file.toString())
                syncDb = connection
                PortfolioApplication(connection, System::currentTimeMillis).also { syncApp = it }
            }
        }

    private fun closeSyncConnection() {
        synchronized(hostLock) {
            syncApp = null
            syncDb?.close()
            syncDb = null
        }
    }

    private fun bitcoinTransport(
        application: PortfolioApplication,
        endpoint: String = application.sourceEndpoint(),
    ): EsploraTransport =
        io.polion.core.bitcoin.FailoverEsploraTransport(
            io.polion.core.bitcoin.esploraEndpoints(endpoint).map { url ->
                io.polion.core.bitcoin.EsploraEndpoint(
                    url,
                    BlockstreamTransport(
                        url,
                        onProgress = { syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = it) },
                        ensureActive = {
                            if (closing.get() || !application.sourceConsent() || application.sourceEndpoint() != endpoint) {
                                throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                            }
                        },
                    ),
                )
            },
        )

    override fun verifyAccountingOrder(onResult: (String) -> Unit) {
        runSync(SyncStatus(SyncPhase.SYNCING, "Verifying Bitcoin order…", io.polion.ui.SyncOperation.ORDER), onResult) { application ->
            runCatching {
                application.verifyAccountingOrder(bitcoinTransport(application))
                SyncStatus(SyncPhase.SUCCESS, "Bitcoin order verified. Mixed-source chronology may still require review.")
            }.getOrElse {
                SyncStatus(
                    SyncPhase.ERROR,
                    (it as? BitcoinProviderException)?.reason?.userMessage
                        ?: "Could not verify order. Check consent, provider and saved history.",
                )
            }
        }
    }

    override fun discoverWallet(
        walletId: io.polion.core.model.TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Discovering wallet addresses…", io.polion.ui.SyncOperation.DISCOVERY),
            onResult,
        ) { application ->
            val endpoint = application.sourceEndpoint()
            discoverWalletStatus(application, walletId, bitcoinTransport(application, endpoint), providerEndpoint = endpoint) { detail ->
                syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
            }
        }
    }

    override fun fetchHistoricalPrice(
        asset: io.polion.core.model.AssetId,
        quote: io.polion.core.model.AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Fetching daily historical price…", io.polion.ui.SyncOperation.PRICES),
            onResult,
        ) { application ->
            try {
                val result = application.fetchHistoricalPrice({ url -> priceTransport.get(url, apiKey) }, asset, quote, dayUtcMillis)
                if (result.status == io.polion.core.market.PriceStatus.MISSING) {
                    SyncStatus(SyncPhase.ERROR, "No historical quote was returned. Saved prices are unchanged.")
                } else {
                    SyncStatus(SyncPhase.SUCCESS, "Daily historical price saved from CoinGecko.")
                }
            } catch (failure: io.polion.core.market.CoingeckoProviderException) {
                SyncStatus(SyncPhase.ERROR, failure.failure.message)
            } catch (_: Exception) {
                SyncStatus(SyncPhase.ERROR, "Could not fetch a historical price. Check consent, date, connection and storage.")
            }
        }
    }

    override fun fetchPriceHistory(
        asset: io.polion.core.model.AssetId,
        quote: io.polion.core.model.AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Fetching Bitcoin price history…", io.polion.ui.SyncOperation.PRICES),
            onResult,
        ) { application ->
            try {
                val count = application.fetchPriceHistory({ url -> priceTransport.get(url, apiKey) }, asset, quote, days)
                SyncStatus(SyncPhase.SUCCESS, "Saved $count Bitcoin price points from CoinGecko.")
            } catch (failure: io.polion.core.market.CoingeckoProviderException) {
                SyncStatus(SyncPhase.ERROR, failure.failure.message)
            } catch (_: Exception) {
                SyncStatus(SyncPhase.ERROR, "Could not fetch price history. Check consent, connection and storage.")
            }
        }
    }

    override fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    override fun refreshEvm(onResult: (String) -> Unit) = refresh(false, onResult, evmOnly = true)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
        evmOnly: Boolean = false,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, if (recoverHistory) "Recovering full Bitcoin history…" else "Fetching public history…"),
            onResult,
        ) { application ->
            runCatching {
                // Each source refreshes only with its own consent; with neither, nothing is fetched.
                val bitcoinOn = application.sourceConsent() && !evmOnly
                val evmOn = application.evmConsent() && application.evmTargets().isNotEmpty()
                if (!bitcoinOn && !evmOn) return@runCatching SyncStatus(SyncPhase.IDLE, "Allow a provider before syncing")
                // Sources refresh through the connector registry; Bitcoin discovers pending wallets before history.
                val connectors = mutableListOf<SourceConnector>()
                if (bitcoinOn) {
                    val endpoint = application.sourceEndpoint()
                    connectors +=
                        BitcoinConnector(bitcoinTransport(application, endpoint), endpoint) { progress ->
                            syncStatus.value =
                                SyncStatus(
                                    SyncPhase.SYNCING,
                                    "Bitcoin · " +
                                        when (progress.phase) {
                                            "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                                            "verify" -> "Checking chain consistency…"
                                            "apply" -> "Applying verified history…"
                                            else ->
                                                "Address ${progress.addressIndex}/${progress.totalAddresses} · " +
                                                    "${progress.pages} pages · ${progress.transactions} transactions"
                                        },
                                )
                        }
                }
                if (evmOn) connectors += EvmConnector(alchemyTransport(application)::post)
                val outcomes =
                    application.refreshSources(
                        SourceRegistry(connectors),
                        SourceRefreshOptions(
                            recoverHistory = recoverHistory,
                            onProgress = { syncStatus.value = SyncStatus(SyncPhase.SYNCING, "EVM · $it") },
                            withoutConsent =
                                setOfNotNull(
                                    io.polion.core.bitcoin.BITCOIN_MAINNET
                                        .takeIf { !application.sourceConsent() },
                                    io.polion.core.evm.EVM_FAMILY
                                        .takeIf { !application.evmConsent() },
                                ),
                        ),
                    )
                val states = outcomes.flatMap { it.jobs }
                val discoveryNotes = outcomes.flatMap { it.notes }
                // Once a chain has refreshed, new tokens are linked to coins and priced.
                if (outcomes.any { it.connector == EVM_MANIFEST.id && it.jobs.contains(JobState.COMPLETED) }) {
                    refreshEvmCatalogAndPrices(application)
                }
                when {
                    states.isEmpty() ->
                        SyncStatus(
                            SyncPhase.IDLE,
                            if (bitcoinOn) {
                                "Add a public address to sync"
                            } else {
                                discoveryNotes.joinToString(" ").ifEmpty { "Add a public address to sync" }
                            },
                        )
                    states.any {
                        it != io.polion.core.sync.JobState.COMPLETED
                    } -> SyncStatus(SyncPhase.ERROR, application.sourceFailureMessage() ?: "History is incomplete; retry.")
                    else ->
                        SyncStatus(
                            SyncPhase.SUCCESS,
                            when {
                                recoverHistory -> "History recovery complete; coverage remains unverified"
                                application.lastRefreshSkipped() -> "Checked for new activity; none found."
                                else -> "Sync complete; coverage remains unverified"
                            } + discoveryNotes.joinToString("") { " $it" },
                        )
                }
            }.getOrElse {
                SyncStatus(
                    SyncPhase.ERROR,
                    (it as? BitcoinProviderException)?.reason?.userMessage
                        ?: "Could not finish sync. Check storage and provider, then retry.",
                )
            }
        }
    }

    /** Alchemy requests for EVM history; a refresh stops at its next request once the app closes or EVM consent is withdrawn. */
    private fun alchemyTransport(application: PortfolioApplication): AlchemyTransport =
        AlchemyTransport(
            apiKey = { application.alchemyApiKey() },
            beforeRequest = { check(!closing.get() && application.evmConsent()) { "EVM refresh was cancelled. Retry if needed." } },
        )

    /**
     * Refreshes the CoinGecko chain catalog, then linked-coin prices, logos and missing price history. Each step is isolated, so a failure leaves the
     * caller's refresh successful. Does nothing without market data consent.
     */
    private fun refreshEvmCatalogAndPrices(application: PortfolioApplication) {
        if (!application.marketDataConsent()) return
        runCatching { application.refreshEvmCatalog(priceTransport::feed) }
        runCatching { application.refreshLinkedCoinPrices(priceTransport::feed) }
        runCatching { application.refreshLinkedCoinLogos(priceTransport::feed) }
        runCatching { application.backfillLinkedCoinHistory({ url, key -> priceTransport.get(url, key.orEmpty()) }) }
    }

    override fun refreshPreferences(): io.polion.ui.RefreshPreferences =
        io.polion.ui.RefreshPreferences(
            app.value.automaticRefreshPreference() ?: (Files.exists(refreshPreference) || app.value.automaticPriceConsent()),
            app.value.wifiOnlyRefreshPreference(),
        )

    override fun updateRefreshPreferences(preferences: io.polion.ui.RefreshPreferences) {
        app.value.setRefreshPreferences(preferences.automaticRefresh, preferences.wifiOnly)
        reconcileRefreshPreferences()
    }

    private fun reconcileRefreshPreferences() {
        val preference = refreshPreferences()
        app.value.setRefreshPreferences(preference.automaticRefresh, preference.wifiOnly)
        val plan = io.polion.ui.refreshSchedulePlan(preference, app.value.sourceConsent(), app.value.automaticPriceConsent())
        scheduleWalletRefresh(plan.walletHistory)
        schedulePrices(plan.prices)
    }

    override fun setBackgroundRefresh(enabled: Boolean) = updateRefreshPreferences(refreshPreferences().copy(automaticRefresh = enabled))

    private fun scheduleWalletRefresh(enabled: Boolean) {
        refreshTask?.cancel(false)
        refreshTask =
            if (enabled) {
                // The first run waits out the rest of the interval after a success, so a restart does not repeat it.
                val delay =
                    cooldownDelayMillis(app.value.sourceLastSuccessMillis(), WALLET_REFRESH_INTERVAL_MILLIS, System.currentTimeMillis())
                executor.scheduleWithFixedDelay({
                    refresh { }
                }, delay, WALLET_REFRESH_INTERVAL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                null
            }
    }

    override fun backgroundRefreshEnabled(): Boolean = refreshPreferences().automaticRefresh

    private fun schedulePrices(enabled: Boolean) {
        automaticPriceTask?.cancel(false)
        automaticPriceTask =
            if (enabled && refreshPreferences().automaticRefresh && app.value.automaticPriceConsent() && !closing.get()) {
                val delay =
                    cooldownDelayMillis(
                        app.value.automaticPriceLastRunMillis(),
                        PRICE_REFRESH_INTERVAL_MILLIS,
                        System.currentTimeMillis(),
                    )
                priceExecutor.scheduleWithFixedDelay(
                    ::runAutomaticPriceRefresh,
                    delay,
                    PRICE_REFRESH_INTERVAL_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            } else {
                null
            }
    }

    override fun refreshMarkets(
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) {
        if (closing.get()) {
            onResult("The app is closing.")
            return
        }
        try {
            priceExecutor.execute {
                val message =
                    synchronized(priceGate) {
                        if (priceRestorePaused || closing.get()) {
                            "Market refresh is paused while data is restored."
                        } else {
                            runCatching {
                                JvmSqliteDatabase(file.toString()).use { connection ->
                                    val application = PortfolioApplication(connection, System::currentTimeMillis)
                                    val message = marketRefreshMessage(application.refreshMarketFeeds(priceTransport::feed, maxAgeMillis))
                                    refreshEvmCatalogAndPrices(application)
                                    message
                                }
                            }.getOrElse { "Could not refresh market data." }
                        }
                    }
                dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(message) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onResult("Could not schedule a market refresh.")
        }
    }

    /** Loads the CoinGecko chain catalog on the market worker, under the same pause and closing guards as market refreshes. */
    override fun refreshEvmCatalog(onResult: (String?) -> Unit) {
        if (closing.get()) {
            onResult("The app is closing.")
            return
        }
        try {
            priceExecutor.execute {
                val message =
                    synchronized(priceGate) {
                        if (priceRestorePaused || closing.get()) {
                            MARKET_PAUSED_MESSAGE
                        } else {
                            runCatching {
                                JvmSqliteDatabase(file.toString()).use { connection ->
                                    PortfolioApplication(connection, System::currentTimeMillis).refreshEvmCatalog(priceTransport::feed)
                                }
                                null
                            }.getOrElse { "Could not load the chain list from CoinGecko." }
                        }
                    }
                dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(message) }
            }
        } catch (_: RejectedExecutionException) {
            onResult("Could not schedule a chain list refresh.")
        }
    }

    override fun refreshAutomaticPrices(onResult: (String) -> Unit) {
        if (closing.get() || !manualPricePending.compareAndSet(false, true)) {
            onResult("A price refresh is already queued or the app is closing.")
            return
        }
        if (priceRefreshStatus.value ==
            io.polion.ui.PriceRefreshPhase.IDLE
        ) {
            priceRefreshStatus.value = io.polion.ui.PriceRefreshPhase.QUEUED
        }
        try {
            priceExecutor.execute {
                try {
                    runAutomaticPriceRefresh()
                } finally {
                    manualPricePending.set(false)
                }
            }
            onResult("Price refresh queued.")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            manualPricePending.set(false)
            priceRefreshStatus.value = io.polion.ui.PriceRefreshPhase.IDLE
            onResult("Could not schedule a price refresh.")
        }
    }

    override fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) {
        try {
            priceExecutor.execute {
                val result =
                    synchronized(priceGate) {
                        if (priceRestorePaused || closing.get()) {
                            OrderBookResult.Failed(MARKET_PAUSED_MESSAGE)
                        } else {
                            runCatching {
                                JvmSqliteDatabase(file.toString()).use { connection ->
                                    loadOrderBook(
                                        PortfolioApplication(connection, System::currentTimeMillis),
                                        coinId,
                                        priceTransport::feed,
                                        // Checked before each request, so a restore or close stops the book request early.
                                        KrakenPriceTransport(beforeRequest = { check(!priceRestorePaused && !closing.get()) })::get,
                                    )
                                }
                            }.getOrElse { OrderBookResult.Failed("Could not load Kraken's order book.") }
                        }
                    }
                SwingUtilities.invokeLater { onResult(result) }
            }
        } catch (_: RejectedExecutionException) {
            SwingUtilities.invokeLater { onResult(OrderBookResult.Failed("Could not schedule an order book request.")) }
        }
    }

    override fun checkNotifications(onResult: (Int) -> Unit) {
        try {
            priceExecutor.execute { notificationCheck(onResult) }
        } catch (_: RejectedExecutionException) {
            SwingUtilities.invokeLater { onResult(0) }
        }
    }

    override fun notificationSettingsChanged() {
        if (reconcileNotificationChecks()) checkNotifications()
    }

    /** One check on the price worker with its own connection, then shows what it found and reports the count on the Swing thread. */
    private fun notificationCheck(onResult: (Int) -> Unit) {
        val messages: List<NotificationMessage> =
            runCatching {
                synchronized(priceGate) {
                    if (priceRestorePaused || closing.get()) {
                        emptyList()
                    } else {
                        JvmSqliteDatabase(file.toString()).use { connection ->
                            PortfolioApplication(connection, System::currentTimeMillis).runNotificationCheck(
                                priceTransport::feed,
                                LocalDate.now().toString(),
                                LocalTime.now().hour,
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
        messages.forEach { DesktopNotifications.show(it.title, it.body) }
        dataRevision.update { it + 1 }
        SwingUtilities.invokeLater { onResult(messages.size) }
    }

    /**
     * Runs the periodic check only while an alert or notification setting is on. Returns whether checks are running.
     * Called at launch, when settings change, and after a restore; the schedule is guarded by [hostLock].
     */
    private fun reconcileNotificationChecks(): Boolean {
        val active = !closing.get() && runCatching { app.value.notificationsActive() }.getOrDefault(false)
        synchronized(hostLock) {
            val running = notificationTask?.isDone == false
            if (!active) {
                notificationTask?.cancel(false)
                notificationTask = null
            } else if (!running) {
                notificationTask =
                    runCatching {
                        priceExecutor.scheduleWithFixedDelay(
                            { runCatching { notificationCheck {} } },
                            NOTIFICATION_FIRST_CHECK_MILLIS,
                            NOTIFICATION_INTERVAL_MILLIS,
                            TimeUnit.MILLISECONDS,
                        )
                    }.getOrNull()
            }
        }
        return active
    }

    private fun runAutomaticPriceRefresh() {
        synchronized(priceGate) {
            if (priceRestorePaused) {
                priceRefreshStatus.value = io.polion.ui.PriceRefreshPhase.IDLE
                return
            }
            priceRefreshStatus.value = io.polion.ui.PriceRefreshPhase.RUNNING
            try {
                JvmSqliteDatabase(file.toString()).use { connection ->
                    val application = PortfolioApplication(connection, System::currentTimeMillis)
                    if (application.automaticPriceConsent()) {
                        try {
                            val generation = application.automaticPriceGeneration()
                            val transport =
                                automaticPriceTransport {
                                    check(
                                        !closing.get() &&
                                            !priceRestorePaused &&
                                            application.automaticPriceConsent() &&
                                            application.automaticPriceGeneration() == generation,
                                    )
                                }
                            application.refreshAutomaticPriceSet(transport) { check(!closing.get() && !priceRestorePaused) }
                            application.recordAutomaticPriceStatus(true)
                            // Market lists ride on the same schedule; a failure here never fails the price refresh.
                            if (application.marketDataConsent()) {
                                runCatching { application.refreshMarketFeeds(priceTransport::feed, MARKET_SCHEDULED_AGE_MILLIS) }
                                refreshEvmCatalogAndPrices(application)
                            }
                        } catch (failure: Exception) {
                            if (!closing.get() &&
                                !priceRestorePaused
                            ) {
                                application.recordAutomaticPriceStatus(
                                    false,
                                    failure is io.polion.core.market.KrakenRateLimitedException,
                                )
                            }
                            throw failure
                        } finally {
                            dataRevision.update { it + 1 }
                        }
                    }
                }
            } catch (_: Exception) {
                // Saved observations remain available until a later refresh succeeds.
            } finally {
                priceRefreshStatus.value = io.polion.ui.PriceRefreshPhase.IDLE
            }
        }
    }

    override fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) {
            onResult("Unlock first")
            return
        }
        executor.execute {
            val success = runCatching { lockStore.remove() }.isSuccess
            SwingUtilities.invokeLater {
                onResult(
                    if (success) "Privacy lock removed" else "Secret Service unavailable; privacy lock was not removed",
                )
            }
        }
    }

    override fun checkBitcoinEndpoint(endpoint: String): String? =
        try {
            io.polion.core.bitcoin
                .requireMainnetEndpoint(BlockstreamTransport(endpoint))
            null
        } catch (failure: io.polion.core.bitcoin.BitcoinProviderException) {
            failure.reason.userMessage
        } catch (_: Exception) {
            "Could not check this endpoint. Use an HTTPS Esplora API address."
        }

    override fun lock() {
        if (lockStore.hasLock) locked.value = true
    }

    override fun eraseAndRemoveLock(onResult: (String) -> Unit) {
        executor.execute {
            val success =
                runCatching {
                    app.value.wipe()
                    app.value.setAutomaticRefresh(false)
                    reconcileRefreshPreferences()
                    lockStore.remove()
                }.isSuccess
            SwingUtilities.invokeLater {
                if (success) {
                    locked.value = false
                    dataRevision.update { it + 1 }
                }
                onResult(if (success) "Local data erased and privacy lock removed" else "Could not finish erasing; retry")
            }
        }
    }

    override fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) {
        unlockExecutor.execute {
            val result =
                runCatching {
                    val verifier = lockStore.read()
                    if (LockPassword.verify(password, verifier)) {
                        lockStore.migrate(verifier)
                        UnlockResult(true)
                    } else {
                        UnlockResult(false, "Incorrect password")
                    }
                }.getOrElse { UnlockResult(false, "Secure privacy lock storage is unavailable or damaged") }
            SwingUtilities.invokeLater {
                if (result.success) locked.value = false
                onResult(result)
            }
        }
    }

    override fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    ) {
        executor.execute {
            val success =
                runCatching {
                    check(!lockStore.hasLock)
                    lockStore.write(LockPassword.create(password))
                }.isSuccess
            SwingUtilities.invokeLater {
                onResult(
                    if (success) {
                        "Privacy lock enabled"
                    } else if (password.length <
                        8
                    ) {
                        "Use a password of at least 8 characters"
                    } else {
                        "Secret Service unavailable; privacy lock was not changed"
                    },
                )
            }
        }
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        refreshTask?.cancel(false)
        automaticPriceTask?.cancel(false)
        synchronized(hostLock) { notificationTask?.cancel(false) }
        priceExecutor.shutdownNow()
        syncQueue.shutdown()
        executor.execute {
            // The network worker must finish cancellation and release SQLite before either connection closes.
            while (!syncQueue.awaitTermination(1_000)) Unit
            closeSyncConnection()
            db.close()
        }
        executor.shutdown()
        unlockExecutor.shutdown()
    }
}

fun main(args: Array<String>) {
    val demo = "--demo" in args
    val directory =
        if (demo) {
            Files.createTempDirectory(
                "polion-demo-",
            )
        } else {
            Path.of(System.getProperty("user.home"), ".local", "share", "polion")
        }

    fun dimension(
        option: String,
        fallback: Int,
    ): Int =
        args
            .indexOf(option)
            .takeIf { it >= 0 }
            ?.let { args.getOrNull(it + 1)?.toIntOrNull() }
            ?.coerceIn(320, 4096) ?: fallback
    val host = runCatching { DesktopHost(directory, demo) }.getOrNull()
    application {
        Window(
            onCloseRequest = {
                host?.close()
                exitApplication()
            },
            title = ApplicationInfo.NAME,
            state = WindowState(width = dimension("--width", 1100).dp, height = dimension("--height", 800).dp),
        ) {
            if (host == null) {
                Text("Could not open local data. Check available storage and database compatibility.")
            } else {
                androidx.compose.runtime.DisposableEffect(window) {
                    val listener =
                        object : java.awt.event.WindowFocusListener {
                            // Focus moving to Polion's own dialog or file chooser is not leaving the app.
                            override fun windowLostFocus(event: java.awt.event.WindowEvent) {
                                if (event.oppositeWindow == null) host.left()
                            }

                            override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                                if (event.oppositeWindow == null) host.returned()
                            }
                        }
                    window.addWindowFocusListener(listener)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                PolionApp(host.app.value, host, host.locked.value)
            }
        }
    }
}

/** True when [database] has no pending write-ahead log, so the file alone holds every committed change. */
internal fun walIsEmpty(database: java.nio.file.Path): Boolean {
    val wal = database.resolveSibling("${database.fileName}-wal")
    return !java.nio.file.Files
        .exists(wal) ||
        java.nio.file.Files
            .size(wal) == 0L
}

/** Strict UTF-8 text of at most [maxChars] characters, or null; reads at most four bytes per allowed character. */
internal fun readText(
    input: java.io.InputStream,
    maxChars: Int,
): String? =
    input.use {
        val limit = maxChars.toLong() * 4
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > limit) return null
        }
        val bytes = out.toByteArray()
        val text =
            runCatching {
                java.nio.charset.StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()
        text?.takeIf { value -> value.length <= maxChars }
    }
