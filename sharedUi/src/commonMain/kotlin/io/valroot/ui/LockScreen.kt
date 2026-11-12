package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LockScreen(host: PlatformActions) {
    var forgot by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("valroot", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Text("Welcome back", style = MaterialTheme.typography.headlineMedium)
            Text("Unlock your local portfolio.")
            SecretField("Privacy lock password", password) {
                password = it
                error = null
            }
            Button(onClick = {
                busy = true
                host.unlock(password) { result ->
                    busy = false
                    error = if (result.success) null else result.message ?: "Incorrect password. Your data has not been changed."
                    if (result.success) password = ""
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Unlocking…" else "Unlock") }
            if (remember { host.biometricUnlock() } == BiometricUnlock.ON) {
                OutlinedButton(onClick = {
                    busy = true
                    error = null
                    host.unlockWithBiometrics { result ->
                        busy = false
                        if (!result.success) error = result.message
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("lock-biometric")) { Text("Unlock with fingerprint") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { forgot = true }, enabled = !busy, modifier = Modifier.testTag("lock-forgot")) {
                Text("Forgot password?")
            }
        }
    }
    if (forgot) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) forgot = false },
            title = { Text("Erase this device's Valroot data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The password cannot be recovered. You can erase this device's Valroot data and remove the lock, " +
                            "then restore an encrypted backup if you have one. Funds on the blockchain are unaffected.",
                    )
                    OutlinedTextField(typed, { typed = it }, label = { Text("Type ERASE to confirm") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed == "ERASE" && !busy,
                    onClick = {
                        busy = true
                        host.eraseAndRemoveLock { message ->
                            busy = false
                            forgot = false
                            error = message.takeUnless { it.startsWith("Local data erased") }
                        }
                    },
                    modifier = Modifier.testTag("lock-erase-confirm"),
                ) { Text("Erase and remove lock") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { forgot = false }) { Text("Cancel") } },
        )
    }
}
