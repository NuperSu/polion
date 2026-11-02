package io.polion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.ui.model.ModelAssetOption
import io.polion.ui.model.ModelDraftField
import io.polion.ui.model.ModelTransactionDialog
import io.polion.ui.model.ModelTransactionDialogActions
import io.polion.ui.model.ModelTransactionDialogState
import io.polion.ui.model.ModelTransactionType
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ModelTransactionDialogTest {
    @Test
    fun escapeClosesDialogFromFocusedField() {
        var closed = 0
        val state =
            ModelTransactionDialogState(
                manual = true,
                assets = listOf(ModelAssetOption("btc", "BTC", "Bitcoin")),
                selectedAsset = "btc",
                type = ModelTransactionType.BUY,
                exchange = "Venue",
                pair = "BTC/USD",
                date = "",
                price = "",
                amount = "",
                fee = "",
                notes = "",
            )
        runDesktopComposeUiTest(width = 600, height = 900) {
            setContent {
                ModelTransactionDialog(
                    state,
                    ModelTransactionDialogActions(
                        onClose = { closed++ },
                        onWallet = {},
                        onManual = {},
                        onImport = {},
                        onAsset = {},
                        onType = {},
                        onField = { _, _ -> },
                        onSave = {},
                    ),
                )
            }
            onNodeWithText("Date & time").performScrollTo().performClick().performKeyInput {
                keyDown(Key.Escape)
                keyUp(Key.Escape)
            }
            runOnIdle { assertEquals(1, closed) }
        }
    }

    @Test
    fun methodAndDraftControlsSendTypedActionsAndRespectAvailability() {
        var state by mutableStateOf(
            ModelTransactionDialogState(
                manual = false,
                assets = listOf(ModelAssetOption("btc", "BTC", "Bitcoin"), ModelAssetOption("eth", "ETH", "Ether")),
                selectedAsset = "btc",
                type = ModelTransactionType.BUY,
                exchange = "Venue",
                pair = "BTC/USD",
                date = "",
                price = "",
                amount = "",
                fee = "",
                notes = "",
                canImport = false,
                canSave = false,
            ),
        )
        val events = mutableListOf<String>()
        runDesktopComposeUiTest(width = 600, height = 900) {
            setContent {
                ModelTransactionDialog(
                    state,
                    ModelTransactionDialogActions(
                        onClose = { events += "close" },
                        onWallet = { events += "wallet" },
                        onManual = { state = state.copy(manual = true) },
                        onImport = { events += "import" },
                        onAsset = {
                            events += "asset:$it"
                            state = state.copy(selectedAsset = it)
                        },
                        onType = {
                            events += "type:$it"
                            state = state.copy(type = it)
                        },
                        onField = { field, value ->
                            events += "field:$field:$value"
                            if (field == ModelDraftField.AMOUNT) state = state.copy(amount = value)
                        },
                        onSave = { events += "save" },
                    ),
                )
            }
            onNodeWithTag("choose-csv").assertIsNotEnabled()
            onNodeWithTag("choose-exchange").assertIsNotEnabled()
            onNodeWithTag("choose-manual").performClick()
            onNodeWithText("ETH").performClick()
            onNodeWithText("Sell").performClick()
            onNodeWithText("Amount (Sell)").performScrollTo().performTextInput("1.25")
            onNodeWithTag("preview-save-transaction").assertIsNotEnabled()
            runOnIdle { state = state.copy(canSave = true) }
            onNodeWithTag("preview-save-transaction").performClick()
            onNodeWithContentDescription("Close").performClick()
            runOnIdle {
                assertEquals(listOf("asset:eth", "type:SELL", "field:AMOUNT:1.25", "save", "close"), events)
            }
        }
    }
}
