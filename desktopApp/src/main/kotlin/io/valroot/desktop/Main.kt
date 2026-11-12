package io.valroot.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.valroot.core.ApplicationInfo
import io.valroot.core.alerts.NotificationMessage
import io.valroot.core.application.HostRefresh
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.RefreshSummary
import io.valroot.core.backup.LockPassword
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.bitcoin.BlockstreamTransport
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.market.DefiLlamaTransport
import io.valroot.core.market.HttpCoingeckoTransport
import io.valroot.core.market.KrakenPriceTransport
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.readBoundedText
import io.valroot.core.storage.walIsEmpty
import io.valroot.core.sync.JobState
import io.valroot.ui.MARKET_SCHEDULED_AGE_MILLIS
import io.valroot.ui.OrderBookResult
import io.valroot.ui.PRICE_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.PlatformActions
import io.valroot.ui.SyncPhase
import io.valroot.ui.SyncStatus
import io.valroot.ui.UnlockResult
import io.valroot.ui.ValrootApp
import io.valroot.ui.WALLET_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.cooldownDelayMillis
import io.valroot.ui.loadOrderBook
import io.valroot.ui.marketRefreshMessage
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
    providerEndpoint: String = application.sources.endpoint(),
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
        val result = application.bitcoinWallets.discover(walletId, discoveryTransport, providerEndpoint = providerEndpoint)
        val states =
            application.bitcoin.refresh(transport, providerEndpoint = providerEndpoint) { progress ->
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
        if (states.isEmpty() || states.any { it != io.valroot.core.sync.JobState.COMPLETED }) {
            SyncStatus(SyncPhase.ERROR, application.sources.failureMessage() ?: "Wallet history is incomplete; retry.")
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
                ?: (it as? io.valroot.core.application.BitcoinDiscoveryConflict)?.message
                ?: "Wallet discovery incomplete. Check consent, provider and discovery limits; retry.",
        )
    }

/** Switching windows is routine on desktop, so leaving locks after five minutes unless Settings says otherwise. */
const val DEFAULT_LOCK_DELAY_MILLIS = 300_000L

/** Notification checks start this long after launch while an alert or notification setting is on, then repeat. */
private const val NOTIFICATION_FIRST_CHECK_MILLIS = 30_000L
private const val NOTIFICATION_INTERVAL_MILLIS = 300_000L

/** CoinGecko linked-coin history requests (currencies other than USD) per backfill run, spaced past its transport's 6-second pacing. */
private const val LINKED_HISTORY_REQUESTS_PER_RUN = 24
private const val LINKED_HISTORY_INTERVAL_MILLIS = 6_100L
private const val LINKED_HISTORY_RATE_LIMIT_WAIT_MILLIS = 61_000L

/** DefiLlama USD history requests per backfill run, one a second: enough for a few years of thirty coins. */
private const val LINKED_USD_HISTORY_REQUESTS_PER_RUN = 600
private const val LINKED_USD_HISTORY_INTERVAL_MILLIS = 1_000L

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

    /** Linked-coin history backfills run here, one at a time, so they never hold up a refresh's result. */
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private val historyBackfillQueued = AtomicBoolean(false)
    private val manualPricePending = AtomicBoolean(false)
    override val priceRefreshStatus = MutableStateFlow(io.valroot.ui.PriceRefreshPhase.IDLE)
    private var automaticPriceTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val priceGate = Any()

    @Volatile private var priceRestorePaused = false
    internal val priceReplacementInProgress: Boolean get() = priceRestorePaused
    private val syncQueue = BitcoinSyncQueue()
    private val priceTransport = HttpCoingeckoTransport()
    private val defiLlamaTransport = DefiLlamaTransport()
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
        executor.execute { runCatching { app.value.settings.setLockDelay(millis) } }
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
        if (demo) app.value.demo.seed()
        lockDelay = app.value.settings.lockDelayMillis() ?: DEFAULT_LOCK_DELAY_MILLIS
        if (runCatching { reconcileRefreshPreferences() }.isFailure) {
            runCatching { app.value.prices.recordAutomaticSchedulingFailure() }
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
                val text = runCatching { readBoundedText(Files.newInputStream(path), maxChars) }.getOrNull()
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
                val temp = runCatching { Files.createTempFile(path.toAbsolutePath().parent, ".valroot-backup-", ".tmp") }.getOrNull()
                val message =
                    if (temp == null) {
                        "Could not save backup"
                    } else {
                        try {
                            Files.newOutputStream(temp).buffered().use { out ->
                                app.value.backup.export(secret) { bytes, offset, length -> out.write(bytes, offset, length) }
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
                                app.value.backup.restoreToStaging(stagingDb, { b, o, l -> input.read(b, o, l) }, password.toCharArray())
                            }
                        }
                        java.nio.channels.FileChannel
                            .open(staging, java.nio.file.StandardOpenOption.WRITE)
                            .use { it.force(true) }
                        priceRestorePaused = true
                        pricePaused = true
                        app.value.prices.invalidateAutomaticRequests()
                        synchronized(priceGate) {
                            closeSyncConnection()
                            db.close()
                            try {
                                // Another connection still holding a write-ahead log would replay it into the restored file.
                                check(walIsEmpty(file)) { "Valroot is still writing; retry the restore" }
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
        endpoint: String = application.sources.endpoint(),
    ): EsploraTransport =
        io.valroot.core.bitcoin.FailoverEsploraTransport(
            io.valroot.core.bitcoin.esploraEndpoints(endpoint).map { url ->
                io.valroot.core.bitcoin.EsploraEndpoint(
                    url,
                    BlockstreamTransport(
                        url,
                        onProgress = { syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = it) },
                        ensureActive = {
                            if (closing.get() || !application.sources.consent() || application.sources.endpoint() != endpoint) {
                                throw BitcoinProviderException(BitcoinProviderFailure.CANCELLED)
                            }
                        },
                    ),
                )
            },
        )

    override fun verifyAccountingOrder(onResult: (String) -> Unit) {
        runSync(SyncStatus(SyncPhase.SYNCING, "Verifying Bitcoin order…", io.valroot.ui.SyncOperation.ORDER), onResult) { application ->
            runCatching {
                application.bitcoin.verifyAccountingOrder(bitcoinTransport(application))
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
        walletId: io.valroot.core.model.TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Discovering wallet addresses…", io.valroot.ui.SyncOperation.DISCOVERY),
            onResult,
        ) { application ->
            val endpoint = application.sources.endpoint()
            discoverWalletStatus(application, walletId, bitcoinTransport(application, endpoint), providerEndpoint = endpoint) { detail ->
                syncStatus.value = syncStatus.value.copy(phase = SyncPhase.SYNCING, message = detail)
            }
        }
    }

    override fun fetchHistoricalPrice(
        asset: io.valroot.core.model.AssetId,
        quote: io.valroot.core.model.AssetId,
        dayUtcMillis: Long,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Fetching daily historical price…", io.valroot.ui.SyncOperation.PRICES),
            onResult,
        ) { application ->
            try {
                val result = application.prices.fetchHistorical({ url -> priceTransport.get(url, apiKey) }, asset, quote, dayUtcMillis)
                if (result.status == io.valroot.core.market.PriceStatus.MISSING) {
                    SyncStatus(SyncPhase.ERROR, "No historical quote was returned. Saved prices are unchanged.")
                } else {
                    SyncStatus(SyncPhase.SUCCESS, "Daily historical price saved from CoinGecko.")
                }
            } catch (failure: io.valroot.core.market.CoingeckoProviderException) {
                SyncStatus(SyncPhase.ERROR, failure.failure.message)
            } catch (_: Exception) {
                SyncStatus(SyncPhase.ERROR, "Could not fetch a historical price. Check consent, date, connection and storage.")
            }
        }
    }

    override fun fetchPriceHistory(
        asset: io.valroot.core.model.AssetId,
        quote: io.valroot.core.model.AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, "Fetching Bitcoin price history…", io.valroot.ui.SyncOperation.PRICES),
            onResult,
        ) { application ->
            try {
                val count = application.prices.fetchHistory({ url -> priceTransport.get(url, apiKey) }, asset, quote, days)
                SyncStatus(SyncPhase.SUCCESS, "Saved $count Bitcoin price points from CoinGecko.")
            } catch (failure: io.valroot.core.market.CoingeckoProviderException) {
                SyncStatus(SyncPhase.ERROR, failure.failure.message)
            } catch (_: Exception) {
                SyncStatus(SyncPhase.ERROR, "Could not fetch price history. Check consent, connection and storage.")
            }
        }
    }

    override fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    override fun refreshWithoutBitcoin(onResult: (String) -> Unit) = refresh(false, onResult, withoutBitcoin = true)

    override fun exchangeKeyInfo(
        kind: io.valroot.core.exchange.ExchangeKind,
        key: String,
        secret: String,
    ): io.valroot.core.exchange.ExchangeKeyInfo =
        io.valroot.core.exchange.JvmExchanges
            .of(kind)
            .checkKey(key, secret)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
        withoutBitcoin: Boolean = false,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, if (recoverHistory) "Recovering full Bitcoin history…" else "Fetching public history…"),
            onResult,
        ) { application ->
            runCatching {
                val plan =
                    HostRefresh(application, withoutBitcoin) { source ->
                        check(!closing.get()) { "$source refresh was cancelled. Retry if needed." }
                    }
                if (!plan.anySource) return@runCatching SyncStatus(SyncPhase.IDLE, HostRefresh.NO_PROVIDER)
                val connectors =
                    plan.connectors(
                        bitcoin = {
                            val endpoint = application.sources.endpoint()
                            HostRefresh.bitcoinConnector(bitcoinTransport(application, endpoint), endpoint) {
                                syncStatus.value = SyncStatus(SyncPhase.SYNCING, it)
                            }
                        },
                        refreshCatalog = { if (application.market.consent()) application.evm.refreshCatalog(priceTransport::feed) },
                    )
                val outcomes =
                    plan.run(connectors, recoverHistory) { syncStatus.value = SyncStatus(SyncPhase.SYNCING, it) }
                // Once a chain has refreshed, new tokens are linked to coins and priced.
                if (plan.catalogChanged(outcomes)) refreshEvmCatalogAndPrices(application)
                plan.summary(outcomes, recoverHistory).toSyncStatus()
            }.getOrElse {
                SyncStatus(
                    SyncPhase.ERROR,
                    (it as? BitcoinProviderException)?.reason?.userMessage
                        ?: "Could not finish sync. Check storage and provider, then retry.",
                )
            }
        }
    }

    /**
     * Refreshes the CoinGecko chain catalog, then linked-coin prices (USD from DefiLlama, other currencies from
     * CoinGecko), logos and missing price history. Each step is isolated, so a failure leaves the caller's refresh
     * successful. Does nothing without market data consent.
     */
    private fun refreshEvmCatalogAndPrices(application: PortfolioApplication) {
        if (!application.market.consent()) return
        runCatching { application.evm.refreshCatalog(priceTransport::feed) }
        runCatching { application.linkedCoins.refreshUsdPrices(defiLlamaTransport::get) }
        runCatching { application.linkedCoins.refreshPrices(priceTransport::feed) }
        runCatching { application.linkedCoins.refreshLogos(priceTransport::feed) }
        backfillLinkedCoinHistory()
    }

    /**
     * Fills missing linked-coin price history in the background: USD from DefiLlama, then other currencies from
     * CoinGecko. Later calls while one is queued do nothing.
     */
    private fun backfillLinkedCoinHistory() {
        if (closing.get() || !historyBackfillQueued.compareAndSet(false, true)) return
        runCatching {
            historyExecutor.execute {
                historyBackfillQueued.set(false)
                // An interrupted pause (the app closing) ends the run.
                val usd =
                    runCatching {
                        backfillSteps(LINKED_USD_HISTORY_REQUESTS_PER_RUN, LINKED_USD_HISTORY_INTERVAL_MILLIS) { application ->
                            application.linkedCoins.backfillUsdHistory(defiLlamaTransport::get, maxRequests = 1)
                        }
                    }.getOrDefault(true)
                val other =
                    !closing.get() &&
                        runCatching {
                            backfillSteps(LINKED_HISTORY_REQUESTS_PER_RUN, LINKED_HISTORY_INTERVAL_MILLIS) { application ->
                                application.linkedCoins.backfillHistory(
                                    { url, key -> priceTransport.get(url, key.orEmpty()) },
                                    maxRequests = 1,
                                )
                            }
                        }.getOrDefault(true)
                if (usd || other) dataRevision.update { it + 1 }
            }
        }.onFailure { historyBackfillQueued.set(false) }
    }

    /**
     * Runs [step] up to [steps] times, [intervalMillis] apart, until it reports no work. Each step and its write run under
     * the price gate on a fresh connection, so a restore never meets an open connection; the pauses run outside it. A
     * rate limit is waited out twice at most; any other failure ends the run. Returns whether any step did work.
     */
    private fun backfillSteps(
        steps: Int,
        intervalMillis: Long,
        step: (PortfolioApplication) -> Int,
    ): Boolean {
        var changed = false
        var rateLimits = 0
        repeat(steps) {
            Thread.sleep(intervalMillis)
            val done =
                try {
                    synchronized(priceGate) {
                        if (closing.get() || priceRestorePaused) return changed
                        JvmSqliteDatabase(file.toString()).use { connection ->
                            val application = PortfolioApplication(connection, System::currentTimeMillis)
                            if (!application.market.consent()) return changed
                            step(application)
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is InterruptedException) throw failure
                    val limited =
                        (failure as? io.valroot.core.market.CoingeckoProviderException)?.failure ==
                            io.valroot.core.market.CoingeckoProviderFailure.RATE_LIMITED ||
                            (failure as? io.valroot.core.market.DefiLlamaException)?.rateLimited == true
                    if (!limited || ++rateLimits > 2) return changed
                    Thread.sleep(LINKED_HISTORY_RATE_LIMIT_WAIT_MILLIS)
                    return@repeat
                }
            if (done == 0) return changed
            changed = true
        }
        return changed
    }

    override fun refreshPreferences(): io.valroot.ui.RefreshPreferences =
        io.valroot.ui.RefreshPreferences(
            app.value.settings.automaticRefresh() ?: (Files.exists(refreshPreference) || app.value.prices.automaticConsent()),
            app.value.settings.wifiOnlyRefresh(),
        )

    override fun updateRefreshPreferences(preferences: io.valroot.ui.RefreshPreferences) {
        app.value.settings.setRefreshPreferences(preferences.automaticRefresh, preferences.wifiOnly)
        reconcileRefreshPreferences()
    }

    private fun reconcileRefreshPreferences() {
        val preference = refreshPreferences()
        app.value.settings.setRefreshPreferences(preference.automaticRefresh, preference.wifiOnly)
        val plan = io.valroot.ui.refreshSchedulePlan(preference, app.value.sources.consent(), app.value.prices.automaticConsent())
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
                    cooldownDelayMillis(app.value.sources.lastSuccessMillis(), WALLET_REFRESH_INTERVAL_MILLIS, System.currentTimeMillis())
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
            if (enabled && refreshPreferences().automaticRefresh && app.value.prices.automaticConsent() && !closing.get()) {
                val delay =
                    cooldownDelayMillis(
                        app.value.prices.automaticLastRunMillis(),
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
                                    val message = marketRefreshMessage(application.market.refreshFeeds(priceTransport::feed, maxAgeMillis))
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
                                    PortfolioApplication(connection, System::currentTimeMillis).evm.refreshCatalog(priceTransport::feed)
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
            io.valroot.ui.PriceRefreshPhase.IDLE
        ) {
            priceRefreshStatus.value = io.valroot.ui.PriceRefreshPhase.QUEUED
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
            priceRefreshStatus.value = io.valroot.ui.PriceRefreshPhase.IDLE
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
                            PortfolioApplication(connection, System::currentTimeMillis).notifications.runCheck(
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
        val active = !closing.get() && runCatching { app.value.notifications.active() }.getOrDefault(false)
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
                priceRefreshStatus.value = io.valroot.ui.PriceRefreshPhase.IDLE
                return
            }
            priceRefreshStatus.value = io.valroot.ui.PriceRefreshPhase.RUNNING
            try {
                JvmSqliteDatabase(file.toString()).use { connection ->
                    val application = PortfolioApplication(connection, System::currentTimeMillis)
                    if (application.prices.automaticConsent()) {
                        try {
                            val generation = application.prices.automaticGeneration()
                            val transport =
                                automaticPriceTransport {
                                    check(
                                        !closing.get() &&
                                            !priceRestorePaused &&
                                            application.prices.automaticConsent() &&
                                            application.prices.automaticGeneration() == generation,
                                    )
                                }
                            application.prices.refreshAutomaticSet(transport) { check(!closing.get() && !priceRestorePaused) }
                            application.prices.recordAutomaticStatus(true)
                            // Market lists ride on the same schedule; a failure here never fails the price refresh.
                            if (application.market.consent()) {
                                runCatching { application.market.refreshFeeds(priceTransport::feed, MARKET_SCHEDULED_AGE_MILLIS) }
                                refreshEvmCatalogAndPrices(application)
                            }
                        } catch (failure: Exception) {
                            if (!closing.get() &&
                                !priceRestorePaused
                            ) {
                                application.prices.recordAutomaticStatus(
                                    false,
                                    failure is io.valroot.core.market.KrakenRateLimitedException,
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
                priceRefreshStatus.value = io.valroot.ui.PriceRefreshPhase.IDLE
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
            io.valroot.core.bitcoin
                .requireMainnetEndpoint(BlockstreamTransport(endpoint))
            null
        } catch (failure: io.valroot.core.bitcoin.BitcoinProviderException) {
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
                    app.value.backup.wipe()
                    app.value.prices.setAutomaticRefresh(false)
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
        historyExecutor.shutdownNow()
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
                "valroot-demo-",
            )
        } else {
            dataDirectory(Path.of(System.getProperty("user.home"), ".local", "share"))
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
    val appIcon =
        runCatching {
            androidx.compose.ui.graphics.painter
                .BitmapPainter(appIconImage())
        }.getOrNull()
    application {
        Window(
            onCloseRequest = {
                host?.close()
                exitApplication()
            },
            title = ApplicationInfo.NAME,
            icon = appIcon,
            state = WindowState(width = dimension("--width", 1100).dp, height = dimension("--height", 800).dp),
        ) {
            if (host == null) {
                Text("Could not open local data. Check available storage and database compatibility.")
            } else {
                androidx.compose.runtime.DisposableEffect(window) {
                    val listener =
                        object : java.awt.event.WindowFocusListener {
                            // Focus moving to Valroot's own dialog or file chooser is not leaving the app.
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
                ValrootApp(host.app.value, host, host.locked.value)
            }
        }
    }
}

/**
 * The data folder under [share]. The folder of the app's former name, Polion, is moved to it once, when it does not exist
 * yet; an existing Valroot folder is never merged with it.
 */
internal fun dataDirectory(share: Path): Path {
    val directory = share.resolve("valroot")
    val legacy = share.resolve("polion")
    if (!Files.exists(directory) && Files.isDirectory(legacy)) {
        runCatching { Files.move(legacy, directory, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
    }
    return directory
}

/** The Valroot logo (watch-only wallet), rendered from `design/logo/valroot.svg`. */
internal fun appIconImage(): androidx.compose.ui.graphics.ImageBitmap =
    org.jetbrains.skia.Image
        .makeFromEncoded(requireNotNull(DesktopHost::class.java.getResourceAsStream("/valroot.png")).use { it.readAllBytes() })
        .toComposeImageBitmap()

private fun RefreshSummary.toSyncStatus() =
    SyncStatus(
        when (state) {
            RefreshSummary.State.IDLE -> SyncPhase.IDLE
            RefreshSummary.State.SUCCESS -> SyncPhase.SUCCESS
            RefreshSummary.State.ERROR -> SyncPhase.ERROR
        },
        message,
    )
