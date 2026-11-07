package io.polion.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/** Native file selection for encrypted portable backups. */
class BackupFiles {
    fun save(
        name: String,
        bytes: ByteArray,
    ): Boolean {
        val path = choose(FileDialog.SAVE, "Export file", name) ?: return false
        Files.write(path, bytes)
        return true
    }

    fun chooseBackupDestination(): Path? = choose(FileDialog.SAVE, "Save encrypted backup", "polion-backup.polion")

    fun chooseBackup(): Path? = choose(FileDialog.LOAD, "Restore encrypted backup")

    private fun choose(
        mode: Int,
        title: String,
        name: String = "polion-backup.enc",
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
