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

    fun open(): ByteArray? {
        val path = choose(FileDialog.LOAD, "Import encrypted backup") ?: return null
        require(Files.size(path) <= MAX_BACKUP_BYTES) { "Backup is too large" }
        return Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(MAX_BACKUP_BYTES + 1)
            require(bytes.size <= MAX_BACKUP_BYTES) { "Backup is too large" }
            bytes
        }
    }

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

    companion object {
        private const val MAX_BACKUP_BYTES = 100_000_256
    }
}
