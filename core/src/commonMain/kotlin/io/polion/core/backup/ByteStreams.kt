package io.polion.core.backup

/** Platform-neutral byte output, adapted by hosts from files or document providers. */
fun interface ByteSink {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

/** Platform-neutral byte input; returns -1 at the end of the stream. */
fun interface ByteSource {
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

fun ByteSink.write(bytes: ByteArray) = write(bytes, 0, bytes.size)

/** Reads exactly [length] bytes, or returns false at a clean end of stream before the first byte. */
fun ByteSource.readFully(
    buffer: ByteArray,
    length: Int = buffer.size,
): Boolean {
    var filled = 0
    while (filled < length) {
        val count = read(buffer, filled, length - filled)
        if (count < 0) {
            require(filled == 0) { "Backup ends unexpectedly" }
            return false
        }
        filled += count
    }
    return true
}

/** In-memory sink for small exports and tests. */
class ByteArraySink : ByteSink {
    private var data = ByteArray(8192)
    private var size = 0

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
        bytes.copyInto(data, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** In-memory source for small imports and tests. */
class ByteArraySource(
    private val data: ByteArray,
) : ByteSource {
    private var position = 0

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= data.size) return -1
        val count = minOf(length, data.size - position)
        data.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}

/** Writes length-prefixed UTF-8 fields: a 4-byte big-endian length (-1 for null) followed by the bytes. */
internal class FieldWriter(
    private val sink: ByteSink,
) {
    private val buffer = ByteArray(64 * 1024)
    private var filled = 0

    fun field(value: String?) {
        if (value == null) {
            int(-1)
            return
        }
        val bytes = value.encodeToByteArray()
        int(bytes.size)
        bytes(bytes)
    }

    private fun int(value: Int) {
        bytes(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
    }

    private fun bytes(value: ByteArray) {
        var offset = 0
        while (offset < value.size) {
            if (filled == buffer.size) flush()
            val count = minOf(buffer.size - filled, value.size - offset)
            value.copyInto(buffer, filled, offset, offset + count)
            filled += count
            offset += count
        }
    }

    fun flush() {
        if (filled > 0) sink.write(buffer, 0, filled)
        filled = 0
    }
}

/** Reads fields written by [FieldWriter]; a single field may not exceed [maxFieldBytes]. */
internal class FieldReader(
    private val source: ByteSource,
    private val maxFieldBytes: Int = 64 * 1024 * 1024,
) {
    private val header = ByteArray(4)

    fun next(): String? {
        require(source.readFully(header)) { "Backup ends unexpectedly" }
        val length =
            ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (length == -1) return null
        require(length in 0..maxFieldBytes) { "Malformed backup" }
        val bytes = ByteArray(length)
        require(length == 0 || source.readFully(bytes)) { "Backup ends unexpectedly" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    fun required(): String = requireNotNull(next()) { "Malformed backup" }

    fun requireEnd() {
        require(source.read(ByteArray(1), 0, 1) < 0) { "Unexpected data after the end of the backup" }
    }
}
