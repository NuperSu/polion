package io.polion.android

import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/** Storage Access Framework selection and streamed I/O for exports and encrypted backups. */
class BackupFiles(
    activity: ComponentActivity,
) {
    val isChoosing: Boolean get() = busy

    private val resolver = activity.applicationContext.contentResolver
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var closed = false
    private var createResult: ((Uri?) -> Unit)? = null
    private var openResult: ((Uri?) -> Unit)? = null

    private val create =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            finish(createResult.also { createResult = null }, uri)
        }
    private val open =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            finish(openResult.also { openResult = null }, uri)
        }

    init {
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    closed = true
                    busy = false
                    createResult = null
                    openResult = null
                    executor.shutdownNow()
                    owner.lifecycle.removeObserver(this)
                }
            },
        )
    }

    /** Asks for a new document; [result] receives null when the user cancels. Main thread only. */
    fun chooseDestination(
        name: String,
        result: (Uri?) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        createResult = result
        try {
            create.launch(name)
        } catch (_: Exception) {
            finish(createResult.also { createResult = null }, null)
        }
    }

    /** Asks for an existing document; [result] receives null when the user cancels. Main thread only. */
    fun chooseSource(result: (Uri?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        openResult = result
        try {
            open.launch(arrayOf("application/octet-stream", "*/*"))
        } catch (_: Exception) {
            finish(openResult.also { openResult = null }, null)
        }
    }

    /** Small generated files such as CSV exports. */
    fun save(
        name: String,
        bytes: ByteArray,
        result: (Boolean) -> Unit,
    ) {
        chooseDestination(name) { uri ->
            if (uri == null) {
                result(false)
            } else {
                executor.execute {
                    val success = runCatching { write(uri) { it.write(bytes) } }.isSuccess
                    android.os.Handler(Looper.getMainLooper()).post { if (!closed) result(success) }
                }
            }
        }
    }

    /** Streams into [uri] on the calling thread; a failed write removes the partial document. */
    fun write(
        uri: Uri,
        block: (OutputStream) -> Unit,
    ) {
        try {
            requireNotNull(resolver.openOutputStream(uri, "wt")).buffered().use(block)
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw failure
        }
    }

    /** Streams from [uri] on the calling thread. */
    fun <T> read(
        uri: Uri,
        block: (InputStream) -> T,
    ): T = requireNotNull(resolver.openInputStream(uri)).buffered().use(block)

    private fun finish(
        callback: ((Uri?) -> Unit)?,
        uri: Uri?,
    ) {
        busy = false
        if (!closed) callback?.invoke(uri)
    }
}
