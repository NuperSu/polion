package io.polion.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class ModelTransactionType(
    val label: String,
) {
    BUY("Buy"),
    SELL("Sell"),
    TRANSFER("Transfer"),
    DEPOSIT("Deposit"),
    WITHDRAWAL("Withdrawal"),
    REWARD("Reward"),
    FEE("Fee"),
}

enum class ModelDraftField { DATE, PRICE, AMOUNT, FEE, NOTES }

data class ModelAssetOption(
    val id: String,
    val ticker: String,
    val name: String,
)

data class ModelTransactionDialogState(
    val manual: Boolean,
    val assets: List<ModelAssetOption>,
    val selectedAsset: String,
    val type: ModelTransactionType,
    val exchange: String,
    val pair: String,
    val date: String,
    val price: String,
    val amount: String,
    val fee: String,
    val notes: String,
    val canConnectWallet: Boolean = true,
    val canManual: Boolean = true,
    val canImport: Boolean = false,
    val canSave: Boolean = false,
    val showPrice: Boolean = true,
    val showFee: Boolean = true,
    val saving: Boolean = false,
    val types: List<ModelTransactionType> = listOf(ModelTransactionType.BUY, ModelTransactionType.SELL, ModelTransactionType.TRANSFER),
    val message: String? = null,
    val saveLabel: String = "Done",
    val priceLabel: String = "Price per coin",
    val feeLabel: String = "Transaction fee",
)

data class ModelTransactionDialogActions(
    val onClose: () -> Unit,
    val onWallet: () -> Unit,
    val onManual: () -> Unit,
    val onImport: () -> Unit,
    val onAsset: (String) -> Unit,
    val onType: (ModelTransactionType) -> Unit,
    val onField: (ModelDraftField, String) -> Unit,
    val onSave: () -> Unit,
    val onSupportedManual: (() -> Unit)? = null,
)

@Composable
private fun FormCapsule(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Text(
        label,
        color = if (selected) c.onSurface else c.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .background(
                    if (selected) c.surfaceVariant else Color.Transparent,
                    CircleShape,
                ).border(
                    1.dp,
                    if (focused) c.outline else c.surfaceVariant,
                    CircleShape,
                ).clip(CircleShape)
                .selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

@Composable
private fun MethodCard(
    title: String,
    description: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (enabled) c.surface else c.surface.copy(alpha = .5f),
                RoundedCornerShape(14.dp),
            ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            title,
            color = if (enabled) c.onSurface else c.onSurfaceVariant.copy(alpha = .5f),
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
        )
        Text(description, color = if (enabled) c.onSurfaceVariant else c.onSurfaceVariant.copy(alpha = .5f))
    }
}

@Composable
fun ModelTransactionDialog(
    state: ModelTransactionDialogState,
    actions: ModelTransactionDialogActions,
    controls: @Composable () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val selected = state.assets.firstOrNull { it.id == state.selectedAsset }
    Dialog(onDismissRequest = actions.onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape) {
                        if (event.type == KeyEventType.KeyDown) actions.onClose()
                        true
                    } else {
                        false
                    }
                }.fillMaxSize(),
            color = c.background,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.manual) selected?.name.orEmpty() else "Add to your portfolio",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                    TextButton(
                        onClick = actions.onClose,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Close"
                            },
                    ) { Text("×", color = c.onSurface, fontSize = 28.sp) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (!state.manual) {
                        Text(
                            "How would you like to add transactions?",
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        MethodCard(
                            "Connect Wallet",
                            "Track a public Bitcoin address or wallet",
                            state.canConnectWallet,
                            "choose-wallet",
                            actions.onWallet,
                        )
                        MethodCard(
                            "Connect Exchange Account",
                            "Connect an exchange account to track transactions",
                            false,
                            "choose-exchange",
                            {},
                        )
                        MethodCard(
                            "Add Manual Transaction",
                            "Enter a buy, sell, or transfer",
                            state.canManual,
                            "choose-manual",
                            actions.onManual,
                        )
                        MethodCard(
                            "Import CSV File",
                            "Import transactions from a file",
                            state.canImport,
                            "choose-csv",
                            actions.onImport,
                        )
                    } else {
                        // A dropdown scales to many currencies and coins; a row of capsules does not.
                        var assetMenu by remember { mutableStateOf(false) }
                        Box {
                            FormCapsule(
                                "Asset: ${state.assets.find { it.id == state.selectedAsset }?.ticker ?: state.selectedAsset} ▾",
                                true,
                            ) { assetMenu = true }
                            DropdownMenu(assetMenu, { assetMenu = false }) {
                                state.assets.forEach { asset ->
                                    DropdownMenuItem(
                                        text = { Text(asset.ticker) },
                                        onClick = {
                                            assetMenu = false
                                            actions.onAsset(asset.id)
                                        },
                                    )
                                }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.types.forEach { type ->
                                FormCapsule(
                                    type.label,
                                    type == state.type,
                                ) { actions.onType(type) }
                            }
                        }
                        Text("Exchange\n${state.exchange}", fontSize = 14.sp)
                        HorizontalDivider(color = c.surfaceVariant)
                        Text("Trading pair\n${state.pair}", fontSize = 14.sp)
                        controls()
                        OutlinedTextField(state.date, {
                            actions.onField(ModelDraftField.DATE, it)
                        }, label = { Text("Date & time") }, modifier = Modifier.fillMaxWidth())
                        if (state.showPrice) {
                            OutlinedTextField(state.price, {
                                actions.onField(ModelDraftField.PRICE, it)
                            }, label = { Text(state.priceLabel) }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(state.amount, {
                            actions.onField(ModelDraftField.AMOUNT, it)
                        }, label = { Text("Amount (${state.type.label})") }, modifier = Modifier.fillMaxWidth())
                        if (state.showFee) {
                            OutlinedTextField(state.fee, {
                                actions.onField(ModelDraftField.FEE, it)
                            }, label = { Text(state.feeLabel) }, modifier = Modifier.fillMaxWidth())
                        }
                        actions.onSupportedManual?.let { open -> TextButton(onClick = open) { Text("Record deposit, payment or reward") } }
                        OutlinedTextField(state.notes, {
                            actions.onField(ModelDraftField.NOTES, it)
                        }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                    }
                    state.message?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
                }
                if (state.manual) {
                    Button(
                        onClick = actions.onSave,
                        enabled = state.canSave && !state.saving,
                        modifier = Modifier.fillMaxWidth().testTag("preview-save-transaction"),
                    ) {
                        Text(state.saveLabel)
                    }
                }
            }
        }
    }
}
