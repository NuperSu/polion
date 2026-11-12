package io.valroot.core.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Whether SQLite's write-ahead log next to [database] is missing or empty, so the file holds every committed write. */
fun walIsEmpty(database: Path): Boolean {
    val wal = database.resolveSibling("${database.fileName}-wal")
    return !Files.exists(wal) || Files.size(wal) == 0L
}

/** Strict UTF-8 text of at most [maxChars] characters, or null; reads at most four bytes per allowed character. */
fun readBoundedText(
    input: InputStream,
    maxChars: Int,
): String? =
    input.use {
        val limit = maxChars.toLong() * 4
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > limit) return null
        }
        val text =
            runCatching {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString()
            }.getOrNull()
        text?.takeIf { value -> value.length <= maxChars }
    }
