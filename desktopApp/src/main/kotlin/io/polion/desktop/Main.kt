package io.polion.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.polion.core.ApplicationInfo
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.ui.PlatformActions
import io.polion.ui.PolionApp
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import io.polion.ui.UnlockResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

private class DesktopHost(
    private val directory: Path,
    demo: Boolean,
) : PlatformActions {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val unlockExecutor = Executors.newSingleThreadExecutor()
    private val syncQueue = BitcoinSyncQueue()
    private val priceTransport = DesktopCoingeckoTransport()
    override val dataRevision = MutableStateFlow(0L)
    override val syncStatus = MutableStateFlow(SyncStatus())
    override val hasLock: Boolean get() = lockStore.hasLock
    private val file = directory.resolve("portfolio.sqlite")
    private val verifierFile = directory.resolve("lock-verifier")
    private val lockStore = SecureLockStore(verifierFile)
    private val files = BackupFiles()
    private var db: JvmSqliteDatabase
    private var syncDb: JvmSqliteDatabase? = null
    private var syncApp: PortfolioApplication? = null
    val app: androidx.compose.runtime.MutableState<PortfolioApplication>
    val locked = mutableStateOf(lockStore.hasLock)
    private var refreshTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val hostLock = Any()
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
        if (Files.exists(refreshPreference)) setBackgroundRefresh(true)
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

    override fun openFile(onResult: (ByteArray?) -> Unit) {
        SwingUtilities.invokeLater { onResult(runCatching { files.open() }.getOrNull()) }
    }

    override fun restoreBackup(
        bytes: ByteArray,
        password: String,
        onResult: (String) -> Unit,
    ) {
        executor.execute {
            if (syncQueue.busy) {
                SwingUtilities.invokeLater { onResult("Wait for the active sync to finish, then restore") }
                return@execute
            }
            var staging: Path? = null
            var result = "Could not restore backup"
            var swapped = false
            var reopened = false
            try {
                staging = Files.createTempFile(directory, "restore-", ".sqlite")
                JvmSqliteDatabase(staging.toString()).use { app.value.restoreToStaging(it, bytes, password.toCharArray()) }
                java.nio.channels.FileChannel
                    .open(staging, java.nio.file.StandardOpenOption.WRITE)
                    .use { it.force(true) }
                db.close()
                try {
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
                result = "Backup restored"
            } catch (_: Exception) {
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
                staging?.let { runCatching { Files.deleteIfExists(it) } }
            }
            val message = result
            SwingUtilities.invokeLater { onResult(message) }
        }
    }

    /**
     * Runs one network task on the sync worker with its own database connection, so UI reads, saves and unlock
     * stay on the UI queue. A task is refused while another sync is active.
     */
    private fun runSync(
        start: SyncStatus,
        onResult: (String) -> Unit,
        task: (PortfolioApplication) -> SyncStatus,
    ) {
        syncStatus.value = start
        val accepted =
            syncQueue.tryStart {
                val status =
                    try {
                        task(syncApplication())
                    } catch (failure: Exception) {
                        SyncStatus(
                            SyncPhase.ERROR,
                            (failure as? BitcoinProviderException)?.reason?.userMessage
                                ?: "Could not finish sync. Check storage and provider, then retry.",
                        )
                    }
                syncStatus.value = status
                dataRevision.update { it + 1 }
                SwingUtilities.invokeLater { onResult(status.message) }
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

    override fun verifyAccountingOrder(onResult: (String) -> Unit) {
        runSync(SyncStatus(SyncPhase.SYNCING, "Verifying Bitcoin order…"), onResult) { application ->
            runCatching {
                application.verifyAccountingOrder(BlockstreamTransport(application.sourceEndpoint()))
                SyncStatus(SyncPhase.SUCCESS, "Bitcoin order verified. Mixed-source chronology may still require review.")
            }.getOrElse { SyncStatus(SyncPhase.ERROR, "Could not verify order. Check consent, provider and saved history.") }
        }
    }

    override fun discoverWallet(
        walletId: io.polion.core.model.TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        runSync(SyncStatus(SyncPhase.SYNCING, "Discovering wallet addresses…"), onResult) { application ->
            runCatching {
                val transport = BlockstreamTransport(application.sourceEndpoint())
                val result = application.discoverBitcoinWallet(walletId, transport)
                val states = application.refreshBitcoin(transport)
                check(states.all { it == io.polion.core.sync.JobState.COMPLETED })
                SyncStatus(SyncPhase.SUCCESS, "Wallet discovery complete: ${result.addresses} addresses checked.")
            }.getOrElse {
                SyncStatus(
                    SyncPhase.ERROR,
                    "Wallet discovery incomplete. Check consent, provider and discovery limits; retry.",
                )
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
        runSync(SyncStatus(SyncPhase.SYNCING, "Fetching daily historical price…"), onResult) { application ->
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

    override fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
    ) {
        runSync(
            SyncStatus(SyncPhase.SYNCING, if (recoverHistory) "Recovering full Bitcoin history…" else "Fetching public history…"),
            onResult,
        ) { application ->
            runCatching {
                val states =
                    application.refreshBitcoin(
                        BlockstreamTransport(application.sourceEndpoint()),
                        recoverHistory = recoverHistory,
                    ) { progress ->
                        syncStatus.value =
                            SyncStatus(
                                SyncPhase.SYNCING,
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
                when {
                    states.isEmpty() -> SyncStatus(SyncPhase.IDLE, "Add a public address to sync")
                    states.any {
                        it != io.polion.core.sync.JobState.COMPLETED
                    } -> SyncStatus(SyncPhase.ERROR, application.sourceFailureMessage() ?: "History is incomplete; retry.")
                    else ->
                        SyncStatus(
                            SyncPhase.SUCCESS,
                            if (recoverHistory) {
                                "History recovery complete; coverage remains unverified"
                            } else {
                                "Sync complete; coverage remains unverified"
                            },
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

    override fun setBackgroundRefresh(enabled: Boolean) {
        if (enabled) Files.writeString(refreshPreference, "enabled") else Files.deleteIfExists(refreshPreference)
        refreshTask?.cancel(false)
        refreshTask =
            if (enabled) {
                executor.scheduleWithFixedDelay({
                    refresh { }
                }, 30, 30, java.util.concurrent.TimeUnit.MINUTES)
            } else {
                null
            }
    }

    override fun backgroundRefreshEnabled(): Boolean = Files.exists(refreshPreference)

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

    override fun lock() {
        if (lockStore.hasLock) locked.value = true
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
        syncQueue.shutdown()
        closeSyncConnection()
        executor.execute { db.close() }
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
                PolionApp(host.app.value, host, host.locked.value)
            }
        }
    }
}
