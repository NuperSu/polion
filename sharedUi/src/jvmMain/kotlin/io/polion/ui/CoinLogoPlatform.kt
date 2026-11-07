package io.polion.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap

internal actual fun decodeLogo(bytes: ByteArray): ImageBitmap? =
    runCatching {
        org.jetbrains.skia.Image
            .makeFromEncoded(bytes)
            .toComposeImageBitmap()
    }.getOrNull()

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
