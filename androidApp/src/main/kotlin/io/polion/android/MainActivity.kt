package io.polion.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.polion.core.ConsoleLogger
import io.polion.core.LogEvent
import io.polion.ui.PolionApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConsoleLogger.log(LogEvent.APPLICATION_STARTED)
        setContent { PolionApp() }
    }
}
