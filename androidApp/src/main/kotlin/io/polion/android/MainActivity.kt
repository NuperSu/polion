package io.polion.android

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.core.content.edit
import androidx.core.view.WindowInsetsControllerCompat
import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.storage.AndroidSqliteDatabase
import io.polion.ui.PlatformActions
import io.polion.ui.PolionApp
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private var host: AndroidHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        val opened = runCatching { AndroidHost(this) }.getOrNull()
        host = opened
        setContent {
            if (opened == null) Text("Could not open local data. Check available storage and database compatibility.")
            else PolionApp(opened.app.value, opened, opened.locked.value)
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

private class AndroidHost(private val activity: ComponentActivity) : PlatformActions {
    private val executor = RefreshWorker.executor
    override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    override val dataRevision = RefreshWorker.dataRevision
    override val syncStatus = RefreshWorker.syncStatus
    override val hasLock: Boolean get() = preferences.contains("verifier")
    private val main = Handler(Looper.getMainLooper())
    private val file = activity.getDatabasePath("portfolio.sqlite").toPath()
    private val preferences = activity.getSharedPreferences("privacy", 0)
    private val files = BackupFiles(activity)
    val isChoosing: Boolean get() = files.isChoosing
    private var db: AndroidSqliteDatabase
    val app: androidx.compose.runtime.MutableState<PortfolioApplication>
    val locked = mutableStateOf(preferences.contains("verifier"))

    init {
        Files.createDirectories(file.parent)
        db = AndroidSqliteDatabase(file.toString())
        app = try {
            mutableStateOf(PortfolioApplication(db, System::currentTimeMillis))
        } catch (failure: Exception) {
            runCatching { db.close() }
            throw failure
        }
    }

    override fun saveFile(name: String, bytes: ByteArray, onResult: (String) -> Unit) {
        main.post { files.save(name, bytes) { onResult(if (it) "File saved" else "File was not saved") } }
    }
    override fun openFile(onResult: (ByteArray?) -> Unit) { main.post { files.open(onResult) } }
    override fun restoreBackup(bytes: ByteArray, password: String, onResult: (String) -> Unit) {
        executor.execute {
            var staging: java.nio.file.Path? = null
            var result = "Could not restore backup"
            var swapped = false
            var reopened = false
            try {
                staging = Files.createTempFile(file.parent, "restore-", ".sqlite")
                AndroidSqliteDatabase(staging.toString()).use { app.value.restoreToStaging(it, bytes, password.toCharArray()) }
                java.nio.channels.FileChannel.open(staging, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
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
                main.post { app.value = replacement; dataRevision.update { it + 1 }; updated.countDown() }
                updated.await()
                result = "Backup restored"
            } catch (_: Exception) {
                if (swapped) result = "Backup restored; reopen the app to load it"
                if (reopened && !swapped) runCatching {
                    val replacement = PortfolioApplication(db, System::currentTimeMillis)
                    val updated = java.util.concurrent.CountDownLatch(1)
                    main.post { app.value = replacement; dataRevision.update { it + 1 }; updated.countDown() }
                    updated.await()
                }
            } finally {
                staging?.let { runCatching { Files.deleteIfExists(it) } }
            }
            val message = result
            main.post { onResult(message) }
        }
    }
    override fun refresh(onResult: (String) -> Unit) {
        syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Fetching public history…")
        executor.execute {
            val status = runCatching {
                val application = app.value
                val states = application.refreshBitcoin(BlockstreamTransport(application.sourceEndpoint())) { progress ->
                        syncStatus.value = SyncStatus(SyncPhase.SYNCING, when (progress.phase) {
                            "blocks" -> "Checking blocks ${progress.blocksResolved}/${progress.totalBlocks}"
                            "verify" -> "Checking chain consistency…"
                            "apply" -> "Applying verified history…"
                            else -> "Address ${progress.addressIndex}/${progress.totalAddresses} · ${progress.pages} pages · ${progress.transactions} transactions"
                        })
                    }
                when {
                    states.isEmpty() -> SyncStatus(SyncPhase.IDLE, "Add a public address to sync")
                    states.any { it != io.polion.core.sync.JobState.COMPLETED } -> SyncStatus(SyncPhase.ERROR, application.sourceFailureMessage() ?: "History is incomplete; retry.")
                    else -> SyncStatus(SyncPhase.SUCCESS, "Sync complete; coverage remains unverified")
                }
            }.getOrElse { SyncStatus(SyncPhase.ERROR, (it as? BitcoinProviderException)?.reason?.userMessage ?: "Could not finish sync. Check storage and provider, then retry.") }
            syncStatus.value = status
            dataRevision.update { it + 1 }
            main.post { onResult(status.message) }
        }
    }

    override fun setBackgroundRefresh(enabled: Boolean) {
        preferences.edit { putBoolean("background_refresh", enabled) }
        val manager = androidx.work.WorkManager.getInstance(activity)
        if (enabled) {
            val work = androidx.work.PeriodicWorkRequestBuilder<RefreshWorker>(30, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork("polion-refresh", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, work)
        } else {
            manager.cancelUniqueWork("polion-refresh")
        }
    }
    override fun backgroundRefreshEnabled(): Boolean = preferences.getBoolean("background_refresh", false)
    override fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) { onResult("Unlock first"); return }
        val success = preferences.edit().remove("verifier").commit()
        onResult(if (success) "Privacy lock removed" else "Could not remove privacy lock")
    }
    override fun lock() { if (preferences.contains("verifier")) locked.value = true }
    override fun unlock(password: String, onResult: (Boolean) -> Unit) {
        executor.execute {
            val ok = LockPassword.verify(password, preferences.getString("verifier", "") ?: "")
            main.post { if (ok) locked.value = false; onResult(ok) }
        }
    }
    @SuppressLint("UseKtx") // The commit result is needed to report persistence failure.
    override fun setLockPassword(password: String, onResult: (String) -> Unit) {
        executor.execute {
            val success = runCatching { preferences.edit().putString("verifier", LockPassword.create(password)).commit() }.getOrDefault(false)
            main.post { onResult(if (success) "Privacy lock enabled" else "Use a password of at least 8 characters") }
        }
    }
    fun close() { executor.execute { db.close() } }
}
