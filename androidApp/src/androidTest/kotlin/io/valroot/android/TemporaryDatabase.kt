package io.valroot.android

import java.io.File

/** Runs [block] with a new database file in [directory], then deletes it with its journal, WAL and shared-memory files. */
internal fun withTemporaryDatabase(
    directory: File,
    block: (File) -> Unit,
) {
    val file = File.createTempFile("valroot-reliability-", ".sqlite", directory)
    try {
        block(file)
    } finally {
        file.delete()
        File(file.path + "-journal").delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }
}
