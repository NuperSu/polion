package io.valroot.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.reconcile.EffectiveActivity

@Composable
internal fun ActivityDialog(
    item: EffectiveActivity,
    data: PortfolioSnapshot,
    evidence: @Composable () -> Unit,
    close: () -> Unit,
    edit: (() -> Unit)? = null,
    reverse: (() -> Unit)? = null,
    save: (ActivityOverlay) -> Unit,
) {
    var label by remember { mutableStateOf(item.label.orEmpty()) }
    var note by remember { mutableStateOf(item.note.orEmpty()) }
    var kind by remember { mutableStateOf(item.activity.kind) }
    val corrections = remember(item.activity.id) { mutableStateMapOf<EventId, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReverse by remember { mutableStateOf(false) }
    FormDialog("Activity details", close) {
        if (item.original.manual && item.activity.status == ActivityStatus.CONFIRMED) {
            edit?.let { action ->
                TextButton(onClick = action, modifier = Modifier.testTag("edit-manual-transaction")) { Text("Edit manual transaction") }
            }
            reverse?.let { action ->
                if (confirmReverse) {
                    Text("Reverse this entry? Its movements stop affecting balances; its history is retained.")
                    TextButton(onClick = action) { Text("Confirm reversal") }
                    TextButton(onClick = { confirmReverse = false }) { Text("Keep entry") }
                } else {
                    TextButton(onClick = { confirmReverse = true }) { Text("Reverse manual transaction") }
                }
            }
        }
        Text(item.activity.id.value, fontSize = 11.sp)
        Text("${item.activity.status.name} · ${item.activity.kind.name}", fontWeight = FontWeight.SemiBold)
        Text(
            "Classification: ${item.classificationOrigin.name.lowercase()}${item.ruleId?.let { " · rule $it" }.orEmpty()}",
            fontSize = 12.sp,
        )
        item.activity.movements.forEach { movement ->
            Text("${movement.amount.atomicUnits} atomic ${movement.amount.assetId.value} · ${movement.role.name}", fontSize = 12.sp)
            Text(
                data.ownership.accounts
                    .find { it.id == movement.accountId }
                    ?.label ?: movement.accountId.value,
                fontSize = 12.sp,
            )
        }
        HorizontalDivider()
        Text("Source evidence", fontWeight = FontWeight.SemiBold)
        Text(
            if (item.original.manual) {
                "Manual entry"
            } else {
                "Decoder ${item.original.decoderVersion}\n${item.original.sources.joinToString(
                    "\n",
                ) { it.value }}"
            },
            fontSize = 11.sp,
        )
        Text(
            "Original quantities: ${item.original.movements.joinToString { it.amount.atomicUnits.toString() }} atomic units",
            fontSize = 11.sp,
        )
        evidence()
        if (item.unresolvedMovementIds.isNotEmpty()) {
            Text(
                "Correction conflict: some original movement IDs could not be matched.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Field("Label", label) { label = it }
        Field("Private note", note) { note = it }
        Choice("Classification correction", ActivityKind.entries.map { it to it.name.lowercase() }, kind) { kind = it }
        item.activity.movements.forEachIndexed { index, movement ->
            val metadata = data.valuations.find { it.asset == movement.amount.assetId }?.metadata
            val unit = if (metadata == null) "atomic units" else "whole ${metadata.symbol}"
            Field("Correct quantity ${index + 1} ($unit; empty = keep)", corrections[movement.id].orEmpty()) {
                corrections[movement.id] = it
            }
            item.original.movements.find { it.id == movement.id }?.let { original ->
                TextButton(onClick = {
                    corrections[movement.id] = metadata?.let { original.amount.decimal(it.decimals).toString() }
                        ?: original.amount.atomicUnits.toString()
                }) { Text("Reset quantity ${index + 1} to source") }
            }
        }
        Text("Corrections are saved separately. Original source amounts and provenance remain available.", fontSize = 12.sp)
        Button(onClick = {
            val amounts = mutableMapOf<EventId, AtomicAmount>()
            for (movement in item.activity.movements) {
                val input = corrections[movement.id].orEmpty()
                val original = item.original.movements.find { it.id == movement.id }
                val amount =
                    if (input.isBlank()) {
                        movement.amount
                    } else {
                        runCatching {
                            val decimals =
                                data.valuations
                                    .find { it.asset == movement.amount.assetId }
                                    ?.metadata
                                    ?.decimals
                            if (decimals == null) {
                                AtomicAmount(movement.amount.assetId, ExactInteger.parse(input))
                            } else {
                                AtomicAmount.fromDecimal(movement.amount.assetId, ExactDecimal.parse(input), decimals)
                            }
                        }.getOrNull()
                    }
                if (amount == null) {
                    error = "Enter an exact quantity in the units shown for each movement."
                    return@Button
                }
                if (original?.amount != amount) amounts[movement.id] = amount
            }
            save(ActivityOverlay(item.activity.id, label.trim().ifEmpty { null }, note.trim().ifEmpty { null }, kind, amounts))
        }, modifier = Modifier.fillMaxWidth()) { Text("Save correction") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
