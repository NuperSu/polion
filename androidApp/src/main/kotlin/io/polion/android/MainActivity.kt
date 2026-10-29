package io.polion.android

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
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.storage.AndroidSqliteDatabase
import io.polion.ui.PlatformActions
import io.polion.ui.PolionApp
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import io.polion.ui.UnlockResult
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
                    PolionApp(app, opened, opened.locked.value)
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
        RefreshWorker.dataRevision.update { it + 1 }
    }

    override fun onStop() {
        host?.let { if (!it.isChoosing) it.lock() }
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
    override val hasLock: Boolean get() = lockStore.hasLock
    private val main = Handler(Looper.getMainLooper())
    private val file = activity.getDatabasePath("portfolio.sqlite").toPath()
    private val preferences = activity.getSharedPreferences("privacy", 0)
    private val lockStore = SecureLockStore(preferences)
    private val files = BackupFiles(activity)
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val isChoosing: Boolean get() = files.isChoosing
    private lateinit var db: AndroidSqliteDatabase
    val app = mutableStateOf<PortfolioApplication?>(null)
    val openFailed = mutableStateOf(false)

    @Volatile private var closed = false
    val locked = mutableStateOf(lockStore.hasLock)

    init {
        executor.execute {
            try {
                Files.createDirectories(file.parent)
                db = AndroidSqliteDatabase(file.toString())
                val opened = PortfolioApplication(db, System::currentTimeMillis)
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

    override fun openFile(onResult: (ByteArray?) -> Unit) {
        main.post { files.open(onResult) }
    }

    override fun restoreBackup(
        bytes: ByteArray,
        password: String,
        onResult: (String) -> Unit,
    ) {
        executor.execute {
            var staging: java.nio.file.Path? = null
            var result = "Could not restore backup"
            var swapped = false
            var reopened = false
            try {
                staging = Files.createTempFile(file.parent, "restore-", ".sqlite")
                AndroidSqliteDatabase(
                    staging.toString(),
                ).use { requireNotNull(app.value).restoreToStaging(it, bytes, password.toCharArray()) }
                java.nio.channels.FileChannel
                    .open(staging, java.nio.file.StandardOpenOption.WRITE)
                    .use { it.force(true) }
                PriceHistoryWorker.invalidate()
                // This short transaction waits for earlier price commits and invalidates their consent generation.
                requireNotNull(app.value).let { it.setPriceSourceConsent(it.priceSourceConsent()) }
                db.close()
                try {
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
                result = "Backup restored"
            } catch (_: Exception) {
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
                onResult("Could not start order verification. Open Polion and retry.")
            }
        }
    }

    override fun discoverWallet(
        walletId: io.polion.core.model.TrackingTargetId,
        onResult: (String) -> Unit,
    ) {
        main.post {
            try {
                syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Discovering wallet addresses…")
                activity.startForegroundService(Intent(activity, BitcoinRefreshService::class.java).putExtra("wallet_id", walletId.value))
                onResult("Wallet discovery continues in the background.")
            } catch (_: Exception) {
                syncStatus.value = SyncStatus(SyncPhase.ERROR, "Could not start wallet discovery. Open Polion and retry.")
                onResult(syncStatus.value.message)
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
        PriceHistoryWorker.fetch(activity.applicationContext, asset, quote, dayUtcMillis, apiKey) { message ->
            main.post { onResult(message) }
        }
    }

    override fun refresh(onResult: (String) -> Unit) = refresh(false, onResult)

    override fun recoverBitcoinHistory(onResult: (String) -> Unit) = refresh(true, onResult)

    private fun refresh(
        recoverHistory: Boolean,
        onResult: (String) -> Unit,
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
                    Intent(activity, BitcoinRefreshService::class.java).putExtra("recover_history", recoverHistory),
                )
                onResult("Sync continues when you switch apps. Progress appears in the notification.")
            } catch (_: Exception) {
                syncStatus.value = SyncStatus(SyncPhase.ERROR, "Android could not start background sync. Open Polion and retry.")
                onResult(syncStatus.value.message)
            }
        }
    }

    override fun setBackgroundRefresh(enabled: Boolean) {
        preferences.edit { putBoolean("background_refresh", enabled) }
        val manager = androidx.work.WorkManager.getInstance(activity)
        if (enabled) {
            val work =
                androidx.work
                    .PeriodicWorkRequestBuilder<RefreshWorker>(30, java.util.concurrent.TimeUnit.MINUTES)
                    .setConstraints(
                        androidx.work.Constraints
                            .Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build(),
                    ).build()
            manager.enqueueUniquePeriodicWork("polion-refresh", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, work)
        } else {
            manager.cancelUniqueWork("polion-refresh")
        }
    }

    override fun backgroundRefreshEnabled(): Boolean = preferences.getBoolean("background_refresh", false)

    override fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) {
            onResult("Unlock first")
            return
        }
        val success = runCatching { lockStore.remove() }.isSuccess
        onResult(if (success) "Privacy lock removed" else "Could not remove privacy lock")
    }

    override fun lock() {
        if (lockStore.hasLock) locked.value = true
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
