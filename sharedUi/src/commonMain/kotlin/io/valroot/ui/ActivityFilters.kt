package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterKinds(
    selected: ActivityKind?,
    onSelect: (ActivityKind?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected == null,
            { onSelect(null) },
            label = { Text("All") },
        )
        ; ActivityKind.entries.forEach { kind ->
            FilterChip(selected == kind, {
                onSelect(kind)
            }, label = { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) })
        }
    }
}

@Composable
internal fun FilterActivityStatus(
    selected: ActivityStatus?,
    onSelect: (ActivityStatus?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("activity-status-filter")) {
            Text("Status: ${selected?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All"} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All statuses") }, onClick = {
                onSelect(null)
                expanded = false
            }, modifier = Modifier.testTag("status-all"))
            ActivityStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(status)
                        expanded = false
                    },
                    modifier = Modifier.testTag("status-${status.name}"),
                )
            }
        }
    }
}

@Composable
internal fun FilterActivityNetwork(
    networks: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("activity-network-filter")) {
            Text("Network: ${selected ?: "All"} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Networks from recorded sources across all accounts") }, onClick = {}, enabled = false)
            DropdownMenuItem(text = { Text("All networks, including manual") }, onClick = {
                onSelect(null)
                expanded = false
            })
            networks.forEach { network ->
                DropdownMenuItem(text = { Text(network) }, onClick = {
                    onSelect(network)
                    expanded = false
                }, modifier = Modifier.testTag("network-$network"))
            }
        }
    }
}

@Composable internal fun AccountSelector(
    data: PortfolioSnapshot,
    selected: AccountId?,
    onSelect: (AccountId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = {
            expanded =
                true
        }) {
            Text(
                data.ownership.accounts
                    .find { it.id == selected }
                    ?.label ?: "All accounts",
            )
            Text(" ▾")
        }
        ; DropdownMenu(expanded, {
            expanded =
                false
        }) {
            DropdownMenuItem({ Text("All accounts") }, {
                onSelect(null)
                expanded = false
            })
            data.ownership.accounts.filter { "valroot:archived" !in it.tags }.forEach { a ->
                DropdownMenuItem({ Text(a.label) }, {
                    onSelect(a.id)
                    expanded =
                        false
                })
            }
        }
    }
}
