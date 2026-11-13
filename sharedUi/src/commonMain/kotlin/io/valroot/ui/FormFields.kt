package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.time.Clock

@Composable
internal fun FormDialog(
    title: String,
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = close) {
        Surface(
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = close) { Text("Close") }
                }
                content()
            }
        }
    }
}

@Composable internal fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        change,
        label = {
            Text(label, fontSize = 12.sp)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(12.dp),
    )
}

/**
 * Keyboard settings for passwords and API keys: the keyboard treats the field as a password, so it neither suggests nor
 * learns what is typed.
 */
internal val SecretKeyboard =
    androidx.compose.foundation.text.KeyboardOptions(
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
        autoCorrectEnabled = false,
    )

@Composable internal fun SecretField(
    label: String,
    value: String,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label, fontSize = 12.sp) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = SecretKeyboard,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable internal fun <T> Choice(
    label: String,
    choices: List<Pair<T, String>>,
    selected: T,
    change: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 11.sp)
        Box {
            OutlinedButton(onClick = {
                expanded =
                    true
            }, modifier = Modifier.fillMaxWidth()) {
                Text(choices.find { it.first == selected }?.second ?: "Select")
                Text(" ▾")
            }
            ; DropdownMenu(expanded, {
                expanded =
                    false
            }) {
                choices.forEach { (id, name) ->
                    DropdownMenuItem({ Text(name) }, {
                        change(id)
                        expanded = false
                    })
                }
            }
        }
    }
}

internal fun uniqueId(prefix: String) = "$prefix-${Clock.System.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(1_000_000)}"
