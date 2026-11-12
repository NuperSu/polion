package io.valroot.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.valroot.core.ApplicationInfo
import io.valroot.ui.ValrootApp
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val demo = "--demo" in args
    val directory =
        if (demo) {
            Files.createTempDirectory(
                "valroot-demo-",
            )
        } else {
            dataDirectory(Path.of(System.getProperty("user.home"), ".local", "share"))
        }

    fun dimension(
        option: String,
        fallback: Int,
    ): Int =
        args
            .indexOf(option)
            .takeIf { it >= 0 }
            ?.let { args.getOrNull(it + 1)?.toIntOrNull() }
            ?.coerceIn(320, 4096) ?: fallback
    val host = runCatching { DesktopHost(directory, demo) }.getOrNull()
    val appIcon =
        runCatching {
            androidx.compose.ui.graphics.painter
                .BitmapPainter(appIconImage())
        }.getOrNull()
    application {
        Window(
            onCloseRequest = {
                host?.close()
                exitApplication()
            },
            title = ApplicationInfo.NAME,
            icon = appIcon,
            state = WindowState(width = dimension("--width", 1100).dp, height = dimension("--height", 800).dp),
        ) {
            if (host == null) {
                Text("Could not open local data. Check available storage and database compatibility.")
            } else {
                androidx.compose.runtime.DisposableEffect(window) {
                    val listener =
                        object : java.awt.event.WindowFocusListener {
                            // Focus moving to Valroot's own dialog or file chooser is not leaving the app.
                            override fun windowLostFocus(event: java.awt.event.WindowEvent) {
                                if (event.oppositeWindow == null) host.left()
                            }

                            override fun windowGainedFocus(event: java.awt.event.WindowEvent) {
                                if (event.oppositeWindow == null) host.returned()
                            }
                        }
                    window.addWindowFocusListener(listener)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                ValrootApp(host.app.value, host, host.locked.value)
            }
        }
    }
}

/**
 * The data folder under [share]. The folder of the app's former name, Polion, is moved to it once, when it does not exist
 * yet; an existing Valroot folder is never merged with it.
 */
internal fun dataDirectory(share: Path): Path {
    val directory = share.resolve("valroot")
    val legacy = share.resolve("polion")
    if (!Files.exists(directory) && Files.isDirectory(legacy)) {
        runCatching { Files.move(legacy, directory, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
    }
    return directory
}

/** The Valroot logo (watch-only wallet), rendered from `design/logo/valroot.svg`. */
internal fun appIconImage(): androidx.compose.ui.graphics.ImageBitmap =
    org.jetbrains.skia.Image
        .makeFromEncoded(requireNotNull(DesktopHost::class.java.getResourceAsStream("/valroot.png")).use { it.readAllBytes() })
        .toComposeImageBitmap()
