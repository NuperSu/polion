package io.valroot.android

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.AndroidSqliteDatabase
import io.valroot.core.storage.readBoundedText
import io.valroot.core.storage.walIsEmpty
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File import and export: CSV text in, and encrypted backups out and back in. A restore builds the new database in a
 * staging file and replaces the live one only after it validates.
 */
internal class AndroidBackups(
    private val state: AndroidState,
    private val sync: AndroidSync,
) {
    fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) {
        state.main.post { state.files.save(name, bytes) { onResult(if (it) "File saved" else "File was not saved") } }
    }

    /**
     * Asks for a text file, such as a CSV export, and reads it in the background. [onResult] runs on the main thread with
     * the file name and text, or with null text when the user cancelled, the file is over [maxChars] or is not UTF-8 text.
     */
    fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        state.main.post {
            state.files.chooseSource { uri ->
                if (uri == null) {
                    onResult("", null)
                } else {
                    state.executor.execute {
                        val resolver = state.activity.contentResolver
                        val name =
                            runCatching {
                                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                }
                            }.getOrNull() ?: "import.csv"
                        val text = runCatching { resolver.openInputStream(uri)?.let { readBoundedText(it, maxChars) } }.getOrNull()
                        state.main.post { if (!state.closed) onResult(name, text) }
                    }
                }
            }
        }
    }

    fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        state.main.post {
            state.files.chooseDestination("valroot-backup.valroot") { uri ->
                if (uri == null) {
                    onResult("Backup was not saved")
                } else {
                    state.executor.execute {
                        val secret = password.toCharArray()
                        val success =
                            try {
                                runCatching {
                                    state.files.write(uri) { out ->
                                        requireNotNull(state.app.value).backup.export(secret) { b, o, l -> out.write(b, o, l) }
                                    }
                                }.isSuccess
                            } finally {
                                secret.fill('\u0000')
                            }
                        state.main.post { onResult(if (success) "Backup saved" else "Could not save backup") }
                    }
                }
            }
        }
    }

    fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        state.main.post {
            state.files.chooseSource { uri -> if (uri == null) onResult("No backup selected") else restoreBackup(uri, password, onResult) }
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
                staging = Files.createTempFile(state.file.parent, "restore-", ".sqlite")
                AndroidSqliteDatabase(
                    staging.toString(),
                ).use { stagingDb ->
                    state.files.read(uri) { input ->
                        requireNotNull(state.app.value).backup.restoreToStaging(
                            stagingDb,
                            { b, o, l -> input.read(b, o, l) },
                            password.toCharArray(),
                        )
                    }
                }
                java.nio.channels.FileChannel
                    .open(staging, java.nio.file.StandardOpenOption.WRITE)
                    .use { it.force(true) }
                AutomaticPriceGate.paused = true
                pricePaused = true
                requireNotNull(state.app.value).prices.invalidateAutomaticRequests()
                PriceHistoryWorker.invalidate()
                // This short transaction waits for earlier price commits and invalidates their consent generation.
                requireNotNull(state.app.value).let { it.prices.setSourceConsent(it.prices.sourceConsent()) }
                synchronized(AutomaticPriceGate.lock) {
                    state.db.close()
                    try {
                        // Another connection still holding a write-ahead log would replay it into the restored file.
                        check(walIsEmpty(state.file)) { "Valroot is still writing; retry the restore" }
                        Files.move(staging, state.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        swapped = true
                    } finally {
                        state.db = AndroidSqliteDatabase(state.file.toString())
                        reopened = true
                    }
                    val replacement = PortfolioApplication(state.db, System::currentTimeMillis)
                    val updated = java.util.concurrent.CountDownLatch(1)
                    state.main.post {
                        state.app.value = replacement
                        state.dataRevision.update { it + 1 }
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
                        val replacement = PortfolioApplication(state.db, System::currentTimeMillis)
                        val updated = java.util.concurrent.CountDownLatch(1)
                        state.main.post {
                            state.app.value = replacement
                            state.dataRevision.update { it + 1 }
                            updated.countDown()
                        }
                        updated.await()
                    }
                }
            } finally {
                if (pricePaused) {
                    AutomaticPriceGate.paused = false
                    if (swapped && replacementReady &&
                        runCatching { state.app.value?.let { sync.reconcileRefreshPreferences(it) } }.isFailure
                    ) {
                        result = "Backup restored; automatic price scheduling could not be updated"
                    }
                }
                staging?.let { runCatching { Files.deleteIfExists(it) } }
            }
            val message = result
            state.main.post { onResult(message) }
        }
    }
}
