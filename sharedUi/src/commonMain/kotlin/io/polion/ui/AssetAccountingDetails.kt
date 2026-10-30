package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.application.AccountingView
import io.polion.core.application.AssetValuation
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactInteger
import io.polion.core.model.PortfolioId
import io.polion.core.reconcile.EffectiveActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Instant

@Composable
internal fun AssetAccountingDetails(
    app: PortfolioApplication,
    host: PlatformActions,
    asset: AssetValuation,
    data: PortfolioSnapshot,
    portfolio: PortfolioId?,
    revision: Int,
    onActivity: (EffectiveActivity) -> Unit,
) {
    val hostRevision by host.dataRevision.collectAsState()
    var view by remember(app, asset.asset, portfolio) { mutableStateOf<AccountingView?>(null) }
    var error by remember(app, asset.asset, portfolio) { mutableStateOf(false) }
    var activityRequest by remember(app, asset.asset, portfolio) { mutableStateOf<ActivityId?>(null) }
    var activityError by remember(app, asset.asset, portfolio) { mutableStateOf(false) }
    LaunchedEffect(app, asset.asset, portfolio, revision, hostRevision) {
        view = null
        error = false
        try {
            view = withContext(host.dispatcher) { app.accountingReport(portfolio) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    LaunchedEffect(app, portfolio, activityRequest) {
        val id = activityRequest ?: return@LaunchedEffect
        activityError = false
        try {
            val activity = withContext(host.dispatcher) { app.effectiveActivity(id, portfolio) }
            if (activity == null) activityError = true else onActivity(activity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            activityError = true
        } finally {
            activityRequest = null
        }
    }
    when {
        error -> Text("FIFO details could not be loaded. Your saved data is unchanged.", color = MaterialTheme.colorScheme.error)
        view == null -> Text("Loading FIFO details…", color = Muted)
        else ->
            AssetAccountingContent(
                requireNotNull(view),
                asset.asset,
                asset.metadata?.decimals,
                data.ownership.accounts.associate {
                    it.id to
                        it.label
                },
                { id -> activityRequest = id },
            )
    }
    if (activityRequest != null) Text("Loading activity…", color = Muted, fontSize = 12.sp)
    if (activityError) {
        Text(
            "Activity could not be loaded or is no longer in this portfolio scope.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
    }
}

@Composable
internal fun AssetAccountingContent(
    view: AccountingView,
    asset: AssetId,
    decimals: Int?,
    accounts: Map<io.polion.core.model.AccountId, String>,
    onActivity: (ActivityId) -> Unit,
) {
    val lots = remember(view, asset) { view.report.lots.filter { it.asset == asset } }
    val disposals = remember(view, asset) { view.report.disposals.filter { it.asset == asset } }
    var lotPage by remember(view, asset) { mutableIntStateOf(0) }
    var disposalPage by remember(view, asset) { mutableIntStateOf(0) }
    val quote = view.report.quoteAsset.value

    fun quantity(units: ExactInteger): String = decimals?.let { AtomicAmount(asset, units).decimal(it).toString() } ?: "$units atomic units"

    Column(Modifier.fillMaxWidth().testTag("asset-accounting"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("FIFO cost basis", fontWeight = FontWeight.SemiBold)
        Text(
            "Confirmed, classified activity in the current portfolio scope. Unknown basis and proceeds stay unknown.",
            color = Muted,
            fontSize = 12.sp,
        )
        if (view.missingTreatments.isNotEmpty() || view.invalidTreatments.isNotEmpty()) {
            Text(
                "Some portfolio activity needs classification or correction. Asset lots and gains may be incomplete or unavailable.",
                color = Orange,
                fontSize = 12.sp,
            )
        }
        if (view.orderingUncertain.isNotEmpty()) {
            Text("Some portfolio activity has unverified ordering; FIFO results are provisional.", color = Orange, fontSize = 12.sp)
        }
        Text("Open lots (${lots.size})", fontWeight = FontWeight.SemiBold)
        if (lots.isEmpty()) Text("No classified open lots for this asset.", color = Muted, fontSize = 12.sp)
        lots.drop(lotPage * 10).take(10).forEach { lot ->
            val acquired = Instant.fromEpochMilliseconds(lot.acquiredAt).toString().take(10)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${quantity(lot.quantity)} · ${accounts[lot.account] ?: lot.account.value}", fontWeight = FontWeight.Medium)
                Text("Acquired $acquired UTC · basis ${lot.basis.displayAmount()} $quote", fontSize = 12.sp)
                Text("Lot ${lot.id} · exact basis ${lot.basis ?: "unknown"}", color = Muted, fontSize = 11.sp)
            }
        }
        if (lots.size > 10) AssetAccountingPages(lotPage, lots.size) { lotPage = it }
        HorizontalDivider()
        Text("Disposals (${disposals.size})", fontWeight = FontWeight.SemiBold)
        if (disposals.isEmpty()) Text("No classified disposals for this asset.", color = Muted, fontSize = 12.sp)
        disposals.drop(disposalPage * 10).take(10).forEach { disposal ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${quantity(disposal.quantity)} · ${accounts[disposal.account] ?: disposal.account.value}",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Proceeds ${disposal.proceeds.displayAmount()} · basis ${disposal.basis.displayAmount()} · gain ${disposal.gain.displayAmount()} $quote",
                    fontSize = 12.sp,
                )
                Text("Activity ${disposal.activityId.value}${if (disposal.fee) " · fee" else ""}", color = Muted, fontSize = 11.sp)
                TextButton(onClick = { onActivity(disposal.activityId) }) { Text("View disposal activity") }
                disposal.consumed.forEach { part ->
                    Text(
                        "Consumed ${quantity(part.quantity)} from ${part.lotId ?: "unknown lot"} · exact basis ${part.basis ?: "unknown"}",
                        fontSize = 11.sp,
                    )
                }
            }
        }
        if (disposals.size > 10) AssetAccountingPages(disposalPage, disposals.size) { disposalPage = it }
    }
}

@Composable
private fun AssetAccountingPages(
    page: Int,
    total: Int,
    change: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = page > 0, onClick = { change(page - 1) }) { Text("Previous") }
        TextButton(enabled = (page + 1) * 10 < total, onClick = { change(page + 1) }) { Text("Next") }
        Text("${page + 1} / ${(total + 9) / 10}", fontSize = 12.sp)
    }
}
