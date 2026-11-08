package io.polion.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeLogo(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

internal actual fun downloadLogo(
    url: String,
    maxBytes: Int,
): ByteArray? = LogoDownload.get(url, maxBytes)

internal actual fun chartTime(
    millis: Long,
    spanMillis: Long,
): String {
    val pattern = if (spanMillis > 90L * 86_400_000L) "d MMM yyyy" else "d MMM, HH:mm"
    return java.time.format.DateTimeFormatter
        .ofPattern(pattern, java.util.Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(millis))
}

internal actual fun localToEpochMillis(stamp: io.polion.core.application.LocalStamp): Long =
    java.time.LocalDateTime
        .of(stamp.year, stamp.month, stamp.day, stamp.hour, stamp.minute, stamp.second)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

internal actual fun localZoneName(): String = java.time.ZoneId.systemDefault().id
