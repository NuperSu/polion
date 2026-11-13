package io.valroot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import io.valroot.ui.ValrootApp
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private var host: AndroidHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        val opened = runCatching { AndroidHost(this) }.getOrNull()
        host = opened
        setContent {
            if (opened == null) {
                Text("Could not open local data. Check available storage and database compatibility.")
            } else {
                val app = opened.app.value
                if (app != null) {
                    ValrootApp(app, opened, opened.locked.value)
                } else {
                    val status by opened.syncStatus.collectAsState()
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(if (opened.openFailed.value) "Could not open local data" else "Opening your local portfolio")
                                if (!opened.openFailed.value) LinearProgressIndicator()
                                Text(
                                    if (opened.openFailed.value) {
                                        "Check storage and app compatibility. Saved data is intact."
                                    } else {
                                        status.message
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        host?.returned()
        RefreshWorker.dataRevision.update { it + 1 }
        // Runs in the background and does nothing unless an alert or notification setting is on.
        host?.checkNotifications()
    }

    override fun onStop() {
        host?.let { if (!it.isChoosing) it.left() }
        super.onStop()
    }

    override fun onDestroy() {
        host?.close()
        super.onDestroy()
    }
}
