package io.polion.model

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Polion · UI model · sample data",
            state = rememberWindowState(width = 440.dp, height = 940.dp),
        ) {
            PortfolioModel()
        }
    }
