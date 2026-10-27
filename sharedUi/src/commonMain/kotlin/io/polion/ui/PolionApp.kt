package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.polion.core.ApplicationInfo

@Composable
fun PolionApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(ApplicationInfo.NAME, style = MaterialTheme.typography.headlineLarge)
                Text(ApplicationInfo.PURPOSE, style = MaterialTheme.typography.titleMedium)
                Text(ApplicationInfo.PRIVACY_NOTICE)
                Text("Version ${ApplicationInfo.VERSION}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
