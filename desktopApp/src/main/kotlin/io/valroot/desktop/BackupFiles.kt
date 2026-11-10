package io.valroot.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/** Native file selection for encrypted portable backups, exports and CSV imports. */
class BackupFiles {
    fun save(
        name: String,
        bytes: ByteArray,
    ): Boolean {
        val path = choose(FileDialog.SAVE, "Export file", name) ?: return false
        Files.write(path, bytes)
        return true
    }

    fun chooseBackupDestination(): Path? = choose(FileDialog.SAVE, "Save encrypted backup", "valroot-backup.valroot")

    fun chooseBackup(): Path? = choose(FileDialog.LOAD, "Restore encrypted backup")

    fun chooseImport(): Path? = choose(FileDialog.LOAD, "Import CSV file")

    private fun choose(
        mode: Int,
        title: String,
        name: String = "valroot-backup.enc",
    ): Path? {
        val dialog = FileDialog(null as Frame?, title, mode)
        try {
            if (mode == FileDialog.SAVE) dialog.file = name
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return Path.of(dialog.directory, name)
        } finally {
            dialog.dispose()
        }
    }
}
