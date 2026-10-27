package io.polion.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.polion.core.ApplicationInfo
import io.polion.core.ConsoleLogger
import io.polion.core.LogEvent
import io.polion.ui.PolionApp

fun main() {
    ConsoleLogger.log(LogEvent.APPLICATION_STARTED)
    application {
        Window(onCloseRequest = ::exitApplication, title = ApplicationInfo.NAME) {
            PolionApp()
        }
    }
}
