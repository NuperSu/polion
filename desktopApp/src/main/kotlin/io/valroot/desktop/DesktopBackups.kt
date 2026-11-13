package io.valroot.desktop

import androidx.compose.runtime.MutableState
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.readBoundedText
import io.valroot.core.storage.walIsEmpty
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.SwingUtilities

/**
 * File import and export: saves generated files and encrypted backups, reads text files, and restores a backup into a
 * staging database before it replaces the open one. [reconcileRefreshPreferences] and [reconcileNotificationChecks] run
 * after a successful swap, since restored data can change both schedules.
 */
internal class DesktopBackups(
    private val state: DesktopState,
    private val reconcileRefreshPreferences: () -> Unit,
    private val reconcileNotificationChecks: () -> Unit,
) {
    private val files = BackupFiles()
    private val app: MutableState<PortfolioApplication> get() = state.app

    fun saveFile(
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

    fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseImport() }.getOrNull()
            if (path == null) {
                onResult("", null)
                return@invokeLater
            }
            state.executor.execute {
                val text = runCatching { readBoundedText(Files.newInputStream(path), maxChars) }.getOrNull()
                SwingUtilities.invokeLater { onResult(path.fileName.toString(), text) }
            }
        }
    }

    fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseBackupDestination() }.getOrNull()
            if (path == null) {
                onResult("Backup was not saved")
                return@invokeLater
            }
            state.executor.execute {
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

    fun restoreBackup(
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
        state.executor.execute {
            var message = "Could not restore backup"
            val accepted =
                state.syncQueue.tryRunExclusive {
                    var staging: Path? = null
                    var result = "Could not restore backup"
                    var swapped = false
                    var reopened = false
                    var pricePaused = false
                    var replacementReady = false
                    try {
                        staging = Files.createTempFile(state.directory, "restore-", ".sqlite")
                        JvmSqliteDatabase(staging.toString()).use { stagingDb ->
                            Files.newInputStream(path).buffered().use { input ->
                                app.value.backup.restoreToStaging(stagingDb, { b, o, l -> input.read(b, o, l) }, password.toCharArray())
                            }
                        }
                        java.nio.channels.FileChannel
                            .open(staging, java.nio.file.StandardOpenOption.WRITE)
                            .use { it.force(true) }
                        state.priceRestorePaused = true
                        pricePaused = true
                        app.value.prices.invalidateAutomaticRequests()
                        synchronized(state.priceGate) {
                            state.closeSyncConnection()
                            state.db.close()
                            try {
                                // Another connection still holding a write-ahead log would replay it into the restored file.
                                check(walIsEmpty(state.file)) { "Valroot is still writing; retry the restore" }
                                Files.move(staging, state.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                swapped = true
                            } finally {
                                state.db = JvmSqliteDatabase(state.file.toString())
                                reopened = true
                            }
                            val replacement = PortfolioApplication(state.db, System::currentTimeMillis)
                            SwingUtilities.invokeAndWait {
                                app.value = replacement
                                state.dataRevision.update { it + 1 }
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
                                val replacement = PortfolioApplication(state.db, System::currentTimeMillis)
                                SwingUtilities.invokeAndWait {
                                    app.value = replacement
                                    state.dataRevision.update { it + 1 }
                                }
                            }
                        }
                    } finally {
                        if (pricePaused) {
                            state.priceRestorePaused = false
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
}
