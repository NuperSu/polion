package io.polion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.application.AccountingView
import io.polion.core.application.ActivityFilter
import io.polion.core.application.PortfolioApplication
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.Rational
import io.polion.core.model.Rounding
import io.polion.core.reconcile.EffectiveActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AccountingData(
    val view: AccountingView,
    val activities: List<EffectiveActivity>,
    val owners: Map<AccountId, OwnerId>,
    val activityTotal: Int,
    val decimals: Map<AssetId, Int>,
)

@Composable
internal fun AccountingPanel(
    app: PortfolioApplication,
    host: PlatformActions,
    portfolio: PortfolioId?,
    revision: Int,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AccountingData?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<EffectiveActivity, List<Treatment>>?>(null) }
    var activityPage by remember(portfolio, revision) { mutableIntStateOf(0) }
    var lotPage by remember(portfolio, revision) { mutableIntStateOf(0) }
    var disposalPage by remember(portfolio, revision) { mutableIntStateOf(0) }
    LaunchedEffect(portfolio, revision, activityPage) {
        runCatching {
            withContext(host.dispatcher) {
                val report = app.accountingReport(portfolio)
                val snapshot = app.snapshot(ActivityFilter(portfolio = portfolio, offset = activityPage * 50, limit = 50))
                val owners = snapshot.ownership.accounts.associate { it.id to it.ownerId }
                AccountingData(
                    report,
                    snapshot.activities.filter { it.activity.status == ActivityStatus.CONFIRMED },
                    owners,
                    snapshot.activityTotal,
                    snapshot.valuations
                        .mapNotNull { value ->
                            value.metadata?.let { value.asset to it.decimals }
                        }.toMap(),
                )
            }
        }.fold({
            data = it
            error = null
        }, { error = "Accounting report could not be loaded." })
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cost basis and gains", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "FIFO uses your explicit treatments. Blank cost or proceeds remain unknown; amounts are exact.",
            fontSize = 12.sp,
            color = Muted,
        )
        notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val current = data
        if (current == null) {
            Text("Loading accounting…", fontSize = 12.sp)
        } else {
            val report = current.view.report
            if (current.view.missingTreatments.isNotEmpty()) {
                Text(
                    "${current.view.missingTreatments.size} activities need treatments; affected assets are excluded from FIFO results.",
                    color = Orange,
                    fontSize = 12.sp,
                )
            }
            if (current.view.orderingUncertain.isNotEmpty()) {
                TextButton(onClick = {
                    host.verifyAccountingOrder {
                        notice = it
                        onSaved()
                    }
                }) { Text("Verify Bitcoin transaction order") }
                Text(
                    "Bitcoin block timestamps can affect FIFO order. Basis and gains are provisional.",
                    color = Orange,
                    fontSize = 12.sp,
                )
            }
            if (current.view.invalidTreatments.isNotEmpty()) {
                Text(
                    "${current.view.invalidTreatments.size} activities have conflicting treatments and need review.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
            Text("Open lots · ${report.quoteAsset.value}", fontWeight = FontWeight.SemiBold)
            if (report.lots.isEmpty()) Text("No classified open lots.", color = Muted, fontSize = 12.sp)
            report.lots.drop(lotPage * 10).take(10).forEach { lot ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val quantity =
                        current.decimals[lot.asset]?.let { AtomicAmount(lot.asset, lot.quantity).decimal(it).toString() }
                            ?: "${lot.quantity} atomic units"
                    Text("${lot.asset.value} · $quantity", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text("Account ${lot.account.value} · basis ${lot.basis.displayAmount()} ${report.quoteAsset.value}", fontSize = 12.sp)
                    Text("Lot ${lot.id} · exact basis ${lot.basis ?: "unknown"}", color = Muted, fontSize = 10.sp)
                }
            }
            if (report.lots.size > 10) AccountingPages(lotPage, report.lots.size, 10) { lotPage = it }
            HorizontalDivider()
            Text("Disposals", fontWeight = FontWeight.SemiBold)
            if (report.disposals.isEmpty()) Text("No classified disposals.", color = Muted, fontSize = 12.sp)
            report.disposals.drop(disposalPage * 10).take(10).forEach { disposal ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val quantity =
                        current.decimals[disposal.asset]?.let { AtomicAmount(disposal.asset, disposal.quantity).decimal(it).toString() }
                            ?: "${disposal.quantity} atomic units"
                    Text("${disposal.asset.value} · $quantity", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        "Proceeds ${disposal.proceeds.displayAmount()} · basis ${disposal.basis.displayAmount()} · gain ${disposal.gain.displayAmount()} ${report.quoteAsset.value}",
                        fontSize = 12.sp,
                    )
                    Text(
                        "Exact proceeds ${disposal.proceeds ?: "unknown"} · basis ${disposal.basis ?: "unknown"} · gain ${disposal.gain ?: "unknown"}",
                        color = Muted,
                        fontSize = 10.sp,
                    )
                    Text(
                        "Activity ${disposal.activityId.value} · account ${disposal.account.value}",
                        color = Muted,
                        fontSize = 10.sp,
                    )
                    disposal.consumed.forEach { part ->
                        Text(
                            "Consumed ${part.quantity} atomic from ${part.lotId ?: "unknown lot"} · basis ${part.basis ?: "unknown"}",
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (report.disposals.size > 10) AccountingPages(disposalPage, report.disposals.size, 10) { disposalPage = it }
            HorizontalDivider()
            Text("Activity treatments", fontWeight = FontWeight.SemiBold)
            if (current.activities.isEmpty()) {
                Text(
                    "Confirmed activity appears here for classification.",
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
            current.activities.forEach { item ->
                val status =
                    when (item.activity.id) {
                        in current.view.invalidTreatments -> "Review"
                        in current.view.missingTreatments -> "Needs treatment"
                        else -> "Classified"
                    }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.label ?: item.activity.kind.name.lowercase().replaceFirstChar {
                            it.uppercase()
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                    Text("${item.activity.id.value} · $status", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { withContext(host.dispatcher) { app.treatments(item.activity.id) } }
                                .fold({ saved ->
                                    val quoteMismatch =
                                        saved.any { treatment ->
                                            when (treatment) {
                                                is Treatment.Acquire -> treatment.cost?.asset?.let { it != report.quoteAsset } == true
                                                is Treatment.Dispose -> treatment.proceeds?.asset?.let { it != report.quoteAsset } == true
                                                is Treatment.InternalTransfer -> false
                                            }
                                        }
                                    if (quoteMismatch) {
                                        error =
                                            "This activity has values in a different quote currency. Select that currency before editing."
                                    } else {
                                        error = null
                                        editing = item to saved
                                    }
                                }, { error = "Treatments could not be loaded." })
                        }
                    }) { Text("Edit treatments") }
                }
            }
            if (current.activityTotal > 50) AccountingPages(activityPage, current.activityTotal, 50) { activityPage = it }
        }
    }
    val current = data
    editing?.let { (item, saved) ->
        if (current !=
            null
        ) {
            TreatmentDialog(item, saved, current.owners, current.view.report.quoteAsset, error, close = {
                editing = null
                error = null
            }) { treatments ->
                scope.launch {
                    runCatching { withContext(host.dispatcher) { app.saveTreatments(item.activity.id, treatments) } }
                        .fold({
                            editing = null
                            onSaved()
                        }, { error = "Treatment could not be saved. Check movement amounts and quote values." })
                }
            }
        }
    }
}

@Composable
private fun TreatmentDialog(
    item: EffectiveActivity,
    saved: List<Treatment>,
    owners: Map<AccountId, OwnerId>,
    quote: AssetId,
    saveError: String?,
    close: () -> Unit,
    save: (List<Treatment>) -> Unit,
) {
    val movements = item.activity.movements
    var modes by remember(item.activity.id) {
        mutableStateOf(
            movements.associate {
                it.id to
                    if (it.amount.atomicUnits.sign > 0) "Acquire" else "Dispose"
            },
        )
    }
    var values by remember(item.activity.id) { mutableStateOf(emptyMap<EventId, String>()) }
    var partners by remember(item.activity.id) { mutableStateOf(emptyMap<EventId, EventId>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.activity.id) {
        val m = modes.toMutableMap()
        val v = values.toMutableMap()
        val p = partners.toMutableMap()
        saved.forEach { treatment ->
            when (treatment) {
                is Treatment.Acquire -> {
                    m[treatment.movement] = "Acquire"
                    treatment.cost?.let { v[treatment.movement] = it.amount.toString() }
                }
                is Treatment.Dispose -> {
                    m[treatment.movement] = "Dispose"
                    treatment.proceeds?.let {
                        v[treatment.movement] =
                            it.amount.toString()
                    }
                }
                is Treatment.InternalTransfer -> {
                    m[treatment.outgoing] = "Transfer"
                    p[treatment.outgoing] = treatment.incoming
                }
            }
        }
        modes = m
        values = v
        partners = p
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Accounting treatment") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.activity.id.value, fontSize = 11.sp)
                Text(
                    "Enter total ${quote.value} cost or proceeds. Leave blank if unknown. Fractions such as 1/3 are supported.",
                    fontSize = 12.sp,
                )
                movements.forEach { movement ->
                    val pairedBy = partners.entries.firstOrNull { it.value == movement.id && modes[it.key] == "Transfer" }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "${movement.amount.atomicUnits} atomic ${movement.amount.assetId.value}",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                        Text("Account ${movement.accountId.value} · movement ${movement.id.value}", fontSize = 11.sp)
                        if (pairedBy != null) {
                            Text("Incoming side of transfer from ${pairedBy.key.value}", fontSize = 11.sp)
                        } else if (movement.amount.atomicUnits.sign > 0) {
                            Text("Acquisition", fontSize = 12.sp)
                            OutlinedTextField(values[movement.id].orEmpty(), {
                                values = values + (movement.id to it)
                            }, label = {
                                Text(
                                    "Total cost (${quote.value}), optional",
                                )
                            }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        } else if (movement.amount.atomicUnits.sign < 0) {
                            val candidates =
                                movements.filter { other ->
                                    other.amount.assetId == movement.amount.assetId &&
                                        other.amount.atomicUnits == -movement.amount.atomicUnits &&
                                        other.accountId != movement.accountId &&
                                        owners[other.accountId] != null &&
                                        owners[other.accountId] == owners[movement.accountId]
                                }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    modes[movement.id] != "Transfer",
                                    { modes = modes + (movement.id to "Dispose") },
                                    label = { Text("Disposal") },
                                )
                                if (candidates.isNotEmpty()) {
                                    FilterChip(modes[movement.id] == "Transfer", {
                                        modes =
                                            modes + (movement.id to "Transfer")
                                    }, label = { Text("Transfer") })
                                }
                            }
                            if (modes[movement.id] == "Transfer") {
                                var expanded by remember(movement.id) { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) {
                                        Text(
                                            partners[movement.id]?.value ?: "Select incoming movement",
                                        )
                                    }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        candidates.forEach { candidate ->
                                            DropdownMenuItem(text = {
                                                Text(
                                                    "${candidate.accountId.value} · ${candidate.id.value}",
                                                )
                                            }, onClick = {
                                                partners =
                                                    partners + (movement.id to candidate.id)
                                                ; expanded = false
                                            })
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(values[movement.id].orEmpty(), {
                                    values = values + (movement.id to it)
                                }, label = {
                                    Text(
                                        "Total proceeds (${quote.value}), optional",
                                    )
                                }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        } else {
                            Text(
                                "Zero movement requires a correction before classification.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    fun value(id: EventId): QuoteValue? {
                        val input = values[id].orEmpty().trim()
                        if (input.isEmpty()) return null
                        val amount =
                            if ('/' in input) {
                                val parts = input.split('/')
                                require(parts.size == 2)
                                Rational(ExactInteger.parse(parts[0]), ExactInteger.parse(parts[1]))
                            } else {
                                Rational.fromDecimal(ExactDecimal.parse(input))
                            }
                        require(amount >= Rational.ZERO)
                        return QuoteValue(quote, amount)
                    }
                    val incoming = partners.filter { (id, _) -> modes[id] == "Transfer" }.values
                    require(incoming.distinct().size == incoming.size)
                    movements.mapNotNull { movement ->
                        if (movement.id in
                            incoming
                        ) {
                            null
                        } else if (movement.amount.atomicUnits.sign >
                            0
                        ) {
                            Treatment.Acquire(
                                movement.id,
                                value(movement.id),
                                saved.filterIsInstance<Treatment.Acquire>().firstOrNull { it.movement == movement.id }?.opening ?: false,
                            )
                        } else if (movement.amount.atomicUnits.sign < 0 &&
                            modes[movement.id] == "Transfer"
                        ) {
                            Treatment.InternalTransfer(movement.id, requireNotNull(partners[movement.id]))
                        } else if (movement.amount.atomicUnits.sign <
                            0
                        ) {
                            Treatment.Dispose(
                                movement.id,
                                value(movement.id),
                                saved.filterIsInstance<Treatment.Dispose>().firstOrNull { it.movement == movement.id }?.fee
                                    ?: (movement.role == io.polion.core.ledger.MovementRole.FEE),
                            )
                        } else {
                            throw IllegalArgumentException("Zero movement")
                        }
                    }
                }.fold({ save(it) }, { error = "Choose a unique matching transfer and enter non-negative exact values." })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun AccountingPages(
    page: Int,
    total: Int,
    pageSize: Int,
    change: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { change(page - 1) }, enabled = page > 0) { Text("Previous") }
        TextButton(onClick = { change(page + 1) }, enabled = (page + 1) * pageSize < total) { Text("Next") }
        Text("${page + 1} / ${(total + pageSize - 1) / pageSize}", fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

private fun Rational?.displayAmount(): String {
    if (this == null) return "unknown"
    val negative = numerator.sign < 0
    val digits = numerator.toString().removePrefix("-") + "000"
    var remainder = ExactInteger.ZERO
    val quotient = StringBuilder()
    for (digit in digits) {
        remainder = remainder.timesPowerOfTen(1) + ExactInteger.parse(digit.toString())
        var count = 0
        while (remainder >= denominator) {
            remainder -= denominator
            count++
        }
        quotient.append(count)
    }
    val coefficient = ExactInteger.parse((if (negative) "-" else "") + quotient.toString())
    val rounded = ExactDecimal(coefficient, 3).rescale(2, Rounding.HALF_UP).toString()
    return if (quotient.last() != '0' || remainder.sign != 0) "~$rounded" else rounded
}
