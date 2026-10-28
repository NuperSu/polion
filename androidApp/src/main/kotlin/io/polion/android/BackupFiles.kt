package io.polion.android

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Storage Access Framework selection and background I/O for encrypted portable backups. */
class BackupFiles(
    activity: ComponentActivity,
) {
    val isChoosing: Boolean get() = busy

    private val resolver = activity.applicationContext.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var closed = false
    private var cancellation: CancellationSignal? = null
    private var pendingSave: ByteArray? = null
    private var saveResult: ((Boolean) -> Unit)? = null
    private var openResult: ((ByteArray?) -> Unit)? = null

    private val create =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (!closed && saveResult != null) {
                val bytes = pendingSave
                pendingSave = null
                if (uri == null || bytes == null) {
                    finishSave(false)
                } else {
                    val signal = CancellationSignal()
                    cancellation = signal
                    executor.execute {
                        val success =
                            runCatching {
                                withDocument(uri, "wt", signal) { descriptor ->
                                    descriptor.createOutputStream().use { output ->
                                        var offset = 0
                                        while (offset < bytes.size) {
                                            signal.throwIfCanceled()
                                            val count = minOf(8192, bytes.size - offset)
                                            output.write(bytes, offset, count)
                                            offset += count
                                        }
                                        signal.throwIfCanceled()
                                    }
                                }
                            }.isSuccess
                        main.post { if (!closed) finishSave(success) }
                    }
                }
            }
        }
    private val open =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (!closed && openResult != null) {
                if (uri == null) {
                    finishOpen(null)
                } else {
                    val signal = CancellationSignal()
                    cancellation = signal
                    executor.execute {
                        val bytes =
                            runCatching {
                                withDocument(uri, "r", signal) { descriptor ->
                                    descriptor.createInputStream().use { input ->
                                        ByteArrayOutputStream().use { output ->
                                            val buffer = ByteArray(8192)
                                            var total = 0
                                            while (true) {
                                                signal.throwIfCanceled()
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                total += count
                                                require(total <= MAX_BACKUP_BYTES)
                                                output.write(buffer, 0, count)
                                            }
                                            output.toByteArray()
                                        }
                                    }
                                }
                            }.getOrNull()
                        main.post { if (!closed) finishOpen(bytes) }
                    }
                }
            }
        }

    init {
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    closed = true
                    busy = false
                    pendingSave = null
                    saveResult = null
                    openResult = null
                    cancellation?.cancel()
                    cancellation = null
                    executor.shutdownNow()
                    owner.lifecycle.removeObserver(this)
                }
            },
        )
    }

    fun save(
        name: String,
        bytes: ByteArray,
        result: (Boolean) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        pendingSave = bytes
        saveResult = result
        try {
            create.launch(name)
        } catch (_: Exception) {
            pendingSave = null
            finishSave(false)
        }
    }

    fun open(result: (ByteArray?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        openResult = result
        try {
            open.launch(arrayOf("application/octet-stream", "*/*"))
        } catch (_: Exception) {
            finishOpen(null)
        }
    }

    private fun <T> withDocument(
        uri: Uri,
        mode: String,
        signal: CancellationSignal,
        action: (AssetFileDescriptor) -> T,
    ): T =
        requireNotNull(resolver.openAssetFileDescriptor(uri, mode, signal)).use { descriptor ->
            // Close the native descriptor on destruction as well as on normal I/O completion.
            signal.setOnCancelListener { runCatching { descriptor.close() } }
            try {
                signal.throwIfCanceled()
                action(descriptor)
            } finally {
                signal.setOnCancelListener(null)
            }
        }

    private fun finishSave(success: Boolean) {
        val callback = saveResult
        saveResult = null
        cancellation = null
        busy = false
        callback?.invoke(success)
    }

    private fun finishOpen(bytes: ByteArray?) {
        val callback = openResult
        openResult = null
        cancellation = null
        busy = false
        callback?.invoke(bytes)
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 100_000_256
    }
}
