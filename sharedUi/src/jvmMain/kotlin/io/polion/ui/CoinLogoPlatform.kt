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
