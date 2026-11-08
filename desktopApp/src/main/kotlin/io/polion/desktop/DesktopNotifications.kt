package io.polion.desktop

import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Shows alerts and updates as system notifications. Linux uses `notify-send` when it is on PATH, which reaches the desktop's
 * notification daemon directly. Otherwise a system tray balloon is used where the platform has a tray, and nothing is shown.
 * [show] never throws, so a failed notification does not fail the check that produced it, and it is safe to call from a
 * background thread.
 */
internal object DesktopNotifications {
    private const val SEND_TIMEOUT_SECONDS = 5L
    private val notifySend: String? by lazy { findOnPath("notify-send") }

    /** Created on first use and touched only on the Swing thread. */
    private var trayIcon: TrayIcon? = null

    fun show(
        title: String,
        body: String,
    ) {
        runCatching {
            val command = notifySend
            if (command != null) {
                sendWithNotifySend(command, title, body)
            } else if (SystemTray.isSupported()) {
                SwingUtilities.invokeLater { runCatching { showInTray(title, body) } }
            }
        }
    }

    private fun sendWithNotifySend(
        command: String,
        title: String,
        body: String,
    ) {
        // Arguments go straight to the process without a shell. "--" ends option parsing, so text starting with "-" is shown.
        val process =
            ProcessBuilder(listOf(command, "--app-name=Polion", "--", title, body))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process.outputStream.close()
        if (!process.waitFor(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun showInTray(
        title: String,
        body: String,
    ) {
        val icon =
            trayIcon ?: TrayIcon(trayImage()).also {
                SystemTray.getSystemTray().add(it)
                trayIcon = it
            }
        icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    /** A plain square in the app icon's background colour; the tray needs an image even though it is only a balloon source. */
    private fun trayImage(): BufferedImage =
        BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = Color(0x1B4332)
            graphics.fillRect(0, 0, 16, 16)
            graphics.dispose()
        }

    private fun findOnPath(name: String): String? =
        runCatching {
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparatorChar)
                .filter { it.isNotEmpty() }
                .map { Path.of(it, name) }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                ?.toString()
        }.getOrNull()
}
