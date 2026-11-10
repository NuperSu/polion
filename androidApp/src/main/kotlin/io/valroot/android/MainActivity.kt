package io.valroot.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.WindowInsetsControllerCompat
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.ui.BiometricUnlock
import io.valroot.ui.OrderBookResult
import io.valroot.ui.PRICE_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.PlatformActions
import io.valroot.ui.ValrootApp
import io.valroot.ui.SyncPhase
import io.valroot.ui.SyncStatus
import io.valroot.ui.UnlockResult
import io.valroot.ui.WALLET_REFRESH_INTERVAL_MILLIS
import io.valroot.ui.cooldownDelayMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MainActivity : ComponentActivity() {
    private var host: AndroidHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        val opened = runCatching { AndroidHost(this) }.getOrNull()
        host = opened
        setContent {
            if (opened == null) {
                Text("Could not open local data. Check available storage and database compatibility.")
            } else {
                val app = opened.app.value
                if (app != null) {
                    ValrootApp(app, opened, opened.locked.value)
                } else {
                    val status by opened.syncStatus.collectAsState()
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(if (opened.openFailed.value) "Could not open local data" else "Opening your local portfolio")
                                if (!opened.openFailed.value) LinearProgressIndicator()
                                Text(
                                    if (opened.openFailed.value) {
                                        "Check storage and app compatibility. Saved data is intact."
                                    } else {
                                        status.message
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        host?.returned()
        RefreshWorker.dataRevision.update { it + 1 }
        // Runs in the background and does nothing unless an alert or notification setting is on.
        host?.checkNotifications()
    }

    override fun onStop() {
        host?.let { if (!it.isChoosing) it.left() }
        super.onStop()
    }

    override fun onDestroy() {
        host?.close()
        super.onDestroy()
    }
}

private class AndroidHost(
    private val activity: ComponentActivity,
) : PlatformActions {
    private val executor = RefreshWorker.executor
    override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    override val dataRevision = RefreshWorker.dataRevision
    override val syncStatus = RefreshWorker.syncStatus
    override val priceRefreshStatus = AutomaticPriceWorker.status
    override val hasLock: Boolean get() = lockStore.hasLock
    private val main = Handler(Looper.getMainLooper())
    private val file = activity.getDatabasePath("portfolio.sqlite").toPath()
    private val preferences = activity.getSharedPreferences("privacy", 0)
    private val lockStore = SecureLockStore(preferences)
    private val files = BackupFiles(activity)
    private val biometrics = BiometricLock(activity, preferences)
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val isChoosing: Boolean get() = files.isChoosing || biometrics.prompting

    override val defaultDeviceName: String
        get() =
            android.provider.Settings.Global.getString(activity.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    override fun refreshMarkets(
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) {
        MarketFeedRefresher.refresh(activity, maxAgeMillis) { message -> main.post { if (!closed) onResult(message) } }
    }

    override fun refreshEvmCatalog(onResult: (String?) -> Unit) {
        MarketFeedRefresher.refreshEvmCatalog(activity, closing = { closed }) { message -> main.post { if (!closed) onResult(message) } }
    }

    override fun fetchOrderBook(
        coinId: String,
        onResult: (OrderBookResult) -> Unit,
    ) {
        MarketFeedRefresher.orderBook(activity, coinId, closing = { closed }) { result -> main.post { if (!closed) onResult(result) } }
    }

    override fun checkNotifications(onResult: (Int) -> Unit) {
        MarketFeedRefresher.checkNotifications(activity, closing = { closed }) { count -> main.post { if (!closed) onResult(count) } }
    }

    /**
     * Reschedules the periodic check on the database thread. On the main thread, asks for notification permission when
     * it is missing, then runs a check now.
     */
    override fun notificationSettingsChanged() {
        executor.execute {
            val active = runCatching { app.value?.notificationsActive() }.getOrNull() == true
            NotificationWorker.schedule(activity, active)
            main.post {
                if (active && !closed) {
                    val needsPermission =
                        Build.VERSION.SDK_INT >= 33 &&
                            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    checkNotifications()
                }
            }
        }
    }

    override fun biometricUnlock(): BiometricUnlock = biometrics.status(lockStore.hasLock)

    override fun setBiometricUnlock(
        enabled: Boolean,
        onResult: (String) -> Unit,
    ) {
        if (!enabled) {
            biometrics.disable()
            onResult("Fingerprint unlock off")
        } else if (!lockStore.hasLock || locked.value) {
            onResult("Turn on Passcode Lock first")
        } else {
            biometrics.enable { error -> onResult(if (error == null) "Fingerprint unlock on" else error.ifEmpty { "Fingerprint unlock not changed" }) }
        }
    }

    override fun unlockWithBiometrics(onResult: (UnlockResult) -> Unit) {
        if (biometricUnlock() != BiometricUnlock.ON) {
            onResult(UnlockResult(false, "Fingerprint unlock is off. Unlock with your password."))
            return
        }
        biometrics.unlock { error ->
            if (error == null) locked.value = false
            // An empty message means the user chose the password instead; nothing to report.
            onResult(UnlockResult(error == null, error?.ifEmpty { null }))
        }
    }
    private lateinit var db: AndroidSqliteDatabase
    val app = mutableStateOf<PortfolioApplication?>(null)
    val openFailed = mutableStateOf(false)

    @Volatile private var closed = false
    val locked = mutableStateOf(lockStore.hasLock)

    @Volatile private var lockDelay = 0L
    private var leftAt: Long? = null

    override val lockDelayMillis: Long get() = lockDelay

    override fun setLockDelay(millis: Long) {
        lockDelay = millis
        executor.execute { runCatching { app.value?.setLockDelay(millis) } }
    }

    /** The app went to the background: lock now, or remember when, depending on the delay. */
    fun left() {
        if (lockDelay == 0L) lock() else leftAt = android.os.SystemClock.elapsedRealtime()
    }

    /** The app came back: lock if it was away at least as long as the delay. */
    fun returned() {
        val away = leftAt?.let { android.os.SystemClock.elapsedRealtime() - it }
        leftAt = null
        if (away != null && away >= lockDelay) lock()
    }

    init {
        runCatching {
            androidx.work.WorkManager
                .getInstance(activity)
                .getWorkInfosForUniqueWorkLiveData("valroot-price-refresh-now")
                .observe(activity) { work ->
                    AutomaticPriceWorker.tracker.setWorkState(
                        queued =
                            work.any {
                                it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                                    it.state == androidx.work.WorkInfo.State.BLOCKED
                            },
                        running = work.any { it.state == androidx.work.WorkInfo.State.RUNNING },
                    )
                }
        }
        executor.execute {
            try {
                Files.createDirectories(file.parent)
                db = AndroidSqliteDatabase(file.toString())
                val opened = PortfolioApplication(db, System::currentTimeMillis)
                opened.lockDelayMillis()?.let { lockDelay = it }
                if (runCatching { reconcileRefreshPreferences(opened) }.isFailure) {
                    runCatching { opened.recordAutomaticPriceSchedulingFailure() }
                }
                runCatching { NotificationWorker.schedule(activity, opened.notificationsActive()) }
                main.post { if (!closed) app.value = opened }
            } catch (_: Exception) {
                if (::db.isInitialized) runCatching { db.close() }
                main.post { openFailed.value = true }
            }
        }
    }

    override fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) {
        main.post { files.save(name, bytes) { onResult(if (it) "File saved" else "File was not saved") } }
    }

    override fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        main.post {
            files.chooseSource { uri ->
                if (uri == null) {
                    onResult("", null)
                } else {
                    executor.execute {
                        val resolver = activity.contentResolver
                        val name =
                            runCatching {
                                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                }
                            }.getOrNull() ?: "import.csv"
                        val text = runCatching { resolver.openInputStream(uri)?.let { readText(it, maxChars) } }.getOrNull()
                        main.post { if (!closed) onResult(name, text) }
                    }
                }
            }
        }
    }

    override fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        main.post {
            files.chooseDestination("valroot-backup.valroot") { uri ->
                if (uri == null) {
                    onResult("Backup was not saved")
                } else {
                    executor.execute {
                        val secret = password.toCharArray()
                        val success =
                            try {
                                runCatching {
                                    files.write(uri) { out ->
                                        requireNotNull(app.value).exportBackup(secret) { b, o, l -> out.write(b, o, l) }
                                    }
                                }.isSuccess
                            } finally {
                                secret.fill('\u0000')
                            }
                        main.post { onResult(if (success) "Backup saved" else "Could not save backup") }
                    }
                }
            }
        }
    }

    override fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        main.post {
            files.chooseSource { uri -> if (uri == null) onResult("No backup selected") else restoreBackup(uri, password, onResult) }
        }
    }

    private fun restoreBackup(
        uri: android.net.Uri,
        password: String,
        onResult: (String) -> Unit,
    ) {
        // Restore replaces the database file, so it shares the sync thread and cannot overlap a running scan.
        RefreshWorker.syncExecutor.execute {
            var staging: java.nio.file.Path? = null
            var result = "Could not restore backup"
            var swapped = false
            var reopened = false
            var pricePaused = false
            var replacementReady = false
            try {
                staging = Files.createTempFile(file.parent, "restore-", ".sqlite")
                AndroidSqliteDatabase(
                    staging.toString(),
                ).use { stagingDb ->
                    files.read(uri) { input ->
                        requireNotNull(app.value).restoreToStaging(stagingDb, { b, o, l -> input.read(b, o, l) }, password.toCharArray())
                    }
                }
                java.nio.channels.FileChannel
                    .open(staging, java.nio.file.StandardOpenOption.WRITE)
                    .use { it.force(true) }
                AutomaticPriceGate.paused = true
                pricePaused = true
                requireNotNull(app.value).invalidateAutomaticPriceRequests()
                PriceHistoryWorker.invalidate()
                // This short transaction waits for earlier price commits and invalidates their consent generation.
                requireNotNull(app.value).let { it.setPriceSourceConsent(it.priceSourceConsent()) }
                synchronized(AutomaticPriceGate.lock) {
                    db.close()
                    try {
                                // Another connection still holding a write-ahead log would replay it into the restored file.
                                check(walIsEmpty(file)) { "Valroot is still writing; retry the restore" }
                        Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        swapped = true
                    } finally {
                        db = AndroidSqliteDatabase(file.toString())
                        reopened = true
                    }
                    val replacement = PortfolioApplication(db, System::currentTimeMillis)
                    val updated = java.util.concurrent.CountDownLatch(1)
                    main.post {
                        app.value = replacement
                        dataRevision.update { it + 1 }
                        updated.countDown()
                    }
                    updated.await()
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
                        val updated = java.util.concurrent.CountDownLatch(1)
                        main.post {
                            app.value = replacement
                            dataRevision.update { it + 1 }
                            updated.countDown()
                        }
                        updated.await()
                    }
                }
            } finally {
                if (pricePaused) {
                    AutomaticPriceGate.paused = false
                    if (swapped && replacementReady &&
                        runCatching { app.value?.let { reconcileRefreshPreferences(it) } }.isFailure
                    ) {
                        result = "Backup restored; automatic price scheduling could not be updated"
                    }
                }
                staging?.let { runCatching { Files.deleteIfExists(it) } }
            }
            val message = result
            main.post { onResult(message) }
        }
    }

    override fun verifyAccountingOrder(onResult: (String) -> Unit) {
        main.post {
            try {
                activity.startForegroundService(Intent(activity, BitcoinRefreshService::class.java).putExtra("verify_order", true))
                onResult("Order verification started in the background.")
            } catch (_: Exception) {
                onResult("Could not start order verification. Open Valroot and retry.")
            }
        }
    }

    override fun discoverWallet(
        walletId: io.valroot.core.model.TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        main.post {
            try {
                activity.startForegroundService(Intent(activity, BitcoinRefreshService::class.java).putExtra("wallet_id", walletId.value))
                onResult("Wallet discovery requested. It runs after any current Bitcoin work; progress appears in the notification.")
            } catch (_: Exception) {
                val failure =
                    SyncStatus(
                        SyncPhase.ERROR,
                        "Could not request discovery. Open this wallet in Accounts and choose Discover addresses to retry.",
                        io.valroot.ui.SyncOperation.DISCOVERY,
                    )
                if (syncStatus.value.phase != SyncPhase.SYNCING) syncStatus.value = failure
                onResult(failure.message)
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
        PriceHistoryWorker.fetch(activity.applicationContext, asset, quote, dayUtcMillis, apiKey) { message ->
            main.post { onResult(message) }
        }
    }

    override fun fetchPriceHistory(
        asset: io.valroot.core.model.AssetId,
        quote: io.valroot.core.model.AssetId,
        days: Int,
        apiKey: String,
        onResult: (String) -> Unit,
    ) {
        PriceHistoryWorker.fetchRange(
            activity.applicationContext,
            asset,
            quote,
            days,
            apiKey,
        ) { message -> main.post { onResult(message) } }
    }

    override fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    override fun refreshEvm(onResult: (String) -> Unit) = refresh(false, onResult, evmOnly = true)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
        evmOnly: Boolean = false,
    ) {
        main.post {
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                syncStatus.value =
                    SyncStatus(SyncPhase.SYNCING, if (recoverHistory) "Starting full history recovery…" else "Starting background sync…")
                activity.startForegroundService(
                    Intent(activity, BitcoinRefreshService::class.java)
                        .putExtra("recover_history", recoverHistory)
                        .putExtra("evm_only", evmOnly),
                )
                onResult("Sync continues when you switch apps. Progress appears in the notification.")
            } catch (_: Exception) {
                syncStatus.value = SyncStatus(SyncPhase.ERROR, "Android could not start background sync. Open Valroot and retry.")
                onResult(syncStatus.value.message)
            }
        }
    }

    override val wifiOnlyRefreshSupported: Boolean = true

    override fun refreshPreferences(): io.valroot.ui.RefreshPreferences =
        app.value?.let(::readRefreshPreferences)
            ?: io.valroot.ui.RefreshPreferences(preferences.getBoolean("background_refresh", false))

    private fun readRefreshPreferences(application: PortfolioApplication) =
        io.valroot.ui.RefreshPreferences(
            application.automaticRefreshPreference()
                ?: (preferences.getBoolean("background_refresh", false) || application.automaticPriceConsent()),
            application.wifiOnlyRefreshPreference(),
        )

    override fun updateRefreshPreferences(preferences: io.valroot.ui.RefreshPreferences) {
        val application = app.value ?: return
        application.setRefreshPreferences(preferences.automaticRefresh, preferences.wifiOnly)
        reconcileRefreshPreferences(application)
    }

    private fun reconcileRefreshPreferences(application: PortfolioApplication) {
        val preference = readRefreshPreferences(application)
        application.setRefreshPreferences(preference.automaticRefresh, preference.wifiOnly)
        val plan = io.valroot.ui.refreshSchedulePlan(preference, application.sourceConsent(), application.automaticPriceConsent())
        val now = System.currentTimeMillis()
        scheduleWalletRefresh(
            plan.walletHistory,
            plan.wifiOnly,
            cooldownDelayMillis(application.sourceLastSuccessMillis(), WALLET_REFRESH_INTERVAL_MILLIS, now),
        )
        scheduleAutomaticPrices(
            plan.prices,
            plan.wifiOnly,
            application.automaticPriceConsent(),
            cooldownDelayMillis(application.automaticPriceLastRunMillis(), PRICE_REFRESH_INTERVAL_MILLIS, now),
        )
    }

    override fun setBackgroundRefresh(enabled: Boolean) = updateRefreshPreferences(refreshPreferences().copy(automaticRefresh = enabled))

    private fun scheduleWalletRefresh(
        enabled: Boolean,
        wifiOnly: Boolean,
        initialDelayMillis: Long,
    ) {
        AndroidRefreshScheduling.wallets(activity, enabled, wifiOnly, initialDelayMillis = initialDelayMillis)
    }

    override fun backgroundRefreshEnabled(): Boolean = refreshPreferences().automaticRefresh

    private fun scheduleAutomaticPrices(
        enabled: Boolean,
        wifiOnly: Boolean,
        consent: Boolean,
        initialDelayMillis: Long,
    ) {
        val manager = androidx.work.WorkManager.getInstance(activity)
        AndroidRefreshScheduling.prices(activity, enabled, wifiOnly, initialDelayMillis = initialDelayMillis)
        if (!consent) {
            manager.cancelUniqueWork("valroot-price-refresh-now")
            AutomaticPriceWorker.tracker.setWorkState(queued = false, running = false)
        }
    }

    override fun refreshAutomaticPrices(onResult: (String) -> Unit) {
        if (priceRefreshStatus.value != io.valroot.ui.PriceRefreshPhase.IDLE) {
            onResult("A price refresh is already queued or running.")
            return
        }
        try {
            AutomaticPriceWorker.tracker.setWorkState(queued = true, running = false)
            val work =
                androidx.work
                    .OneTimeWorkRequestBuilder<AutomaticPriceWorker>()
                    .setConstraints(
                        androidx.work.Constraints
                            .Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build(),
                    ).build()
            androidx.work.WorkManager
                .getInstance(activity)
                .enqueueUniqueWork("valroot-price-refresh-now", androidx.work.ExistingWorkPolicy.KEEP, work)
            onResult("Price refresh queued; Android will run it when network access is available.")
        } catch (_: Exception) {
            AutomaticPriceWorker.tracker.setWorkState(queued = false, running = false)
            onResult("Could not schedule a price refresh.")
        }
    }

    override fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) {
            onResult("Unlock first")
            return
        }
        val success = runCatching { lockStore.remove() }.isSuccess
        if (success) biometrics.disable()
        onResult(if (success) "Privacy lock removed" else "Could not remove privacy lock")
    }

    override fun checkBitcoinEndpoint(endpoint: String): String? =
        try {
            io.valroot.core.bitcoin.requireMainnetEndpoint(BlockstreamTransport(endpoint))
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
                    app.value?.let { application ->
                        application.wipe()
                        application.setAutomaticRefresh(false)
                        reconcileRefreshPreferences(application)
                    }
                    lockStore.remove()
                }.isSuccess
            main.post {
                if (success) {
                    biometrics.disable()
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
        executor.execute {
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
            main.post {
                if (result.success) locked.value = false
                onResult(result)
            }
        }
    }

    @SuppressLint("UseKtx") // The commit result is needed to report persistence failure.
    override fun setLockPassword(password: String, onResult: (String) -> Unit) {
        executor.execute {
            val success =
                runCatching {
                    check(!lockStore.hasLock)
                    lockStore.write(LockPassword.create(password))
                }.isSuccess
            main.post {
                onResult(
                    if (success) {
                        "Privacy lock enabled"
                    } else if (password.length <
                        8
                    ) {
                        "Use a password of at least 8 characters"
                    } else {
                        "Secure storage unavailable; privacy lock was not changed"
                    },
                )
            }
        }
    }

    fun close() {
        closed = true
        executor.execute { if (::db.isInitialized) db.close() }
    }
}

/** True when [database] has no pending write-ahead log, so the file alone holds every committed change. */
internal fun walIsEmpty(database: java.nio.file.Path): Boolean {
    val wal = database.resolveSibling("${database.fileName}-wal")
    return !java.nio.file.Files.exists(wal) || java.nio.file.Files.size(wal) == 0L
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
