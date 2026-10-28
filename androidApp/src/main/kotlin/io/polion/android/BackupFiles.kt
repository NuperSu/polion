package io.polion.android

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Storage Access Framework selection for encrypted portable backups. */
class BackupFiles(
    private val activity: ComponentActivity,
) {
    val isChoosing: Boolean get() = pendingSave != null || openResult != null

    private var pendingSave: ByteArray? = null
    private var saveResult: ((Boolean) -> Unit)? = null
    private var openResult: ((ByteArray?) -> Unit)? = null

    private val create =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val bytes = pendingSave
            pendingSave = null
            val success =
                if (uri == null || bytes == null) {
                    false
                } else {
                    runCatching {
                        requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    }.isSuccess
                }
            saveResult?.invoke(success)
            saveResult = null
        }
    private val open =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val bytes =
                if (uri == null) {
                    null
                } else {
                    runCatching {
                        requireNotNull(activity.contentResolver.openInputStream(uri)).use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BACKUP_BYTES)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }.getOrNull()
                }
            openResult?.invoke(bytes)
            openResult = null
        }

    fun save(name: String, bytes: ByteArray, result: (Boolean) -> Unit) {
        check(pendingSave == null && openResult == null)
        pendingSave = bytes
        saveResult = result
        create.launch(name)
    }

    fun open(result: (ByteArray?) -> Unit) {
        check(pendingSave == null && openResult == null)
        openResult = result
        open.launch(arrayOf("application/octet-stream", "*/*"))
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 100_000_256
    }
}
