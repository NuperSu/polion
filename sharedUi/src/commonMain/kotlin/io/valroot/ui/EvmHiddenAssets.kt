package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.market.EvmPlatform
import io.valroot.core.model.AssetId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Review of unknown tokens and NFTs. Hidden ones stay out of values until shown; shown EVM tokens and NFTs can be hidden
 * again. NFTs are listed but never valued.
 */
@Composable
internal fun HiddenAssetsDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    onChanged: (String) -> Unit,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var choices by remember(app) { mutableStateOf<List<EvmPlatform>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app) {
        choices = withContext(host.dispatcher) { app.evmChainChoices() }
    }

    fun setShown(
        asset: AssetId,
        shown: Boolean,
    ) {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(host.dispatcher) { app.setAssetShown(asset, shown) }
                onChanged(if (shown) "Token shown" else "Token hidden")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not change this token. Check available storage, then retry."
            } finally {
                busy = false
            }
        }
    }

    val hiddenTokens = data.hiddenAssets.filterNot { isEvmNft(it.asset) }
    val hiddenNfts = data.hiddenAssets.filter { isEvmNft(it.asset) }
    val shownTokens = data.valuations.filter { isEvmToken(it.asset) }
    val shownNfts = data.valuations.filter { isEvmNft(it.asset) }
    AccountFlowDialog("Hidden tokens and NFTs", busy, close) {
        Text("Unknown tokens and NFTs stay hidden until you show them. Hidden assets are left out of totals.")
        if (data.hiddenAssets.isEmpty() && shownTokens.isEmpty() && shownNfts.isEmpty()) {
            Text("No tokens or NFTs.", modifier = Modifier.testTag("hidden-empty"))
        }
        if (hiddenTokens.isNotEmpty()) {
            SectionHeading("Hidden tokens")
            hiddenTokens.forEach { HiddenAssetRow(it, choices, "Show", enabled = !busy, valued = true) { setShown(it.asset, true) } }
        }
        if (hiddenNfts.isNotEmpty() || shownNfts.isNotEmpty()) {
            SectionHeading("NFTs")
            hiddenNfts.forEach { HiddenAssetRow(it, choices, "Show", enabled = !busy, valued = false) { setShown(it.asset, true) } }
            shownNfts.forEach { HiddenAssetRow(it, choices, "Hide", enabled = !busy, valued = false) { setShown(it.asset, false) } }
        }
        if (shownTokens.isNotEmpty()) {
            SectionHeading("Shown tokens")
            shownTokens.forEach { HiddenAssetRow(it, choices, "Hide", enabled = !busy, valued = true) { setShown(it.asset, false) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("hidden-assets-error")) }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** One token or NFT: symbol, name, chain and quantity, with the action that shows or hides it. */
@Composable
private fun HiddenAssetRow(
    asset: AssetValuation,
    choices: List<EvmPlatform>,
    action: String,
    enabled: Boolean,
    valued: Boolean,
    onAction: () -> Unit,
) {
    val symbol = asset.metadata?.symbol ?: asset.asset.value
    val name = asset.metadata?.name.orEmpty()
    val quantity =
        if (isEvmNft(asset.asset)) {
            "Quantity ${asset.amount.atomicUnits}"
        } else {
            holdingQuantity(asset)
        }
    Row(
        Modifier.fillMaxWidth().testTag("hidden-asset-${asset.asset.value}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(symbol, fontWeight = FontWeight.SemiBold)
            if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.bodySmall)
            Text(assetChainName(asset.asset, choices), style = MaterialTheme.typography.bodySmall)
            Text(quantity.hiddenIf(LocalAmountsHidden.current), style = MaterialTheme.typography.bodySmall)
            if (!valued) Text("Not valued", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            enabled = enabled,
            onClick = onAction,
            modifier = Modifier.testTag("hidden-$action-${asset.asset.value}"),
        ) { Text(action) }
    }
}
