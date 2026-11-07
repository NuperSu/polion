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
