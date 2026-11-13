package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.reconcile.ActivityOverlay
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun ColumnScope.ManualActivityWorkflow(
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    nameState: MutableState<String>,
    amountState: MutableState<String>,
    manualFeeState: MutableState<String>,
    manualTimeState: MutableState<String>,
    manualAccountNameState: MutableState<String>,
    creatingManualAccountState: MutableState<Boolean>,
    errorState: MutableState<String?>,
    accountState: MutableState<AccountId?>,
    selectedKindState: MutableState<ActivityKind>,
    assetState: MutableState<AssetId>,
    execute: (String, () -> Unit) -> Unit,
    close: () -> Unit,
) {
    var name by nameState
    var amount by amountState
    var manualFee by manualFeeState
    var manualTime by manualTimeState
    var manualAccountName by manualAccountNameState
    var creatingManualAccount by creatingManualAccountState
    var error by errorState
    var account by accountState
    var selectedKind by selectedKindState
    var asset by assetState

    Text(
        "Manual entries affect your local ledger. A positive quantity adds funds; a negative quantity removes them. This does not send a transaction.",
        fontSize = 13.sp,
    )
    if (data.ownership.accounts.none { it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags }) {
        Text("Create a local account for your manual entries. No provider connection is required.")
        Field("Account name", manualAccountName) { manualAccountName = it }
        Button(onClick = {
            if (manualAccountName.isBlank()) {
                error = "Enter an account name."
                return@Button
            }
            creatingManualAccount = true
            execute("Manual account created") {
                try {
                    val ownerId =
                        data.ownership.owners
                            .firstOrNull()
                            ?.id ?: OwnerId(uniqueId("owner")).also {
                            app.accounts.saveOwner(Owner(it, "Personal"))
                        }
                    val accountId = AccountId(uniqueId("manual-account"))
                    app.accounts.saveAccount(Account(accountId, ownerId, manualAccountName.trim(), AccountKind.WALLET))
                    account = accountId
                } finally {
                    creatingManualAccount = false
                }
            }
        }, enabled = !creatingManualAccount) { Text("Create manual account") }
    } else {
        Choice(
            "Account",
            data.ownership.accounts
                .filter { it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags }
                .map { it.id to it.label },
            account,
        ) {
            account =
                it
        }
        val manualKinds = listOf(ActivityKind.DEPOSIT, ActivityKind.PAYMENT, ActivityKind.REWARD)
        Choice("Classification", manualKinds.map { it to it.name.lowercase() }, selectedKind) { selectedKind = it }
        Text(
            "Deposits and rewards use positive quantities; payments use negative quantities. Buy/sell trades and transfers between accounts are unavailable in this form.",
            fontSize = 12.sp,
        )
        val manualAssets =
            (data.valuations + data.marketCoins.map { it.detailValuation() })
                .distinctBy { it.asset }
                .filter { it.metadata != null }
        val manualChoices =
            (
                manualAssets.map { it.asset to (it.metadata?.name ?: it.asset.value) } +
                    (AssetId("bitcoin:btc") to "Bitcoin")
            ).distinctBy { it.first }
        Choice("Asset", manualChoices, asset) { asset = it }
        val metadata = manualAssets.find { it.asset == asset }?.metadata
        val decimals = metadata?.decimals ?: if (asset == AssetId("bitcoin:btc")) 8 else null
        val symbol = metadata?.symbol ?: if (asset == AssetId("bitcoin:btc")) "BTC" else asset.value
        Field("$symbol quantity (positive or negative)", amount) { amount = it }
        Field("Fee in $symbol (optional, positive)", manualFee) { manualFee = it }
        Field("Date & time (UTC, ISO 8601; empty = now)", manualTime) { manualTime = it }
        Field("Label (optional)", name) { name = it }
        Button(onClick = {
            val units =
                runCatching {
                    AtomicAmount.fromDecimal(
                        asset,
                        ExactDecimal.parse(amount),
                        requireNotNull(decimals),
                    )
                }.getOrNull()
            if (units == null ||
                units.atomicUnits.sign == 0 ||
                account == null ||
                data.ownership.accounts.none {
                    it.id == account && it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags
                }
            ) {
                error = "Enter a nonzero $symbol quantity with up to ${decimals ?: 0} decimal places."
                return@Button
            }
            if (selectedKind !in manualKinds ||
                (selectedKind == ActivityKind.PAYMENT && units.atomicUnits.sign >= 0) ||
                (selectedKind != ActivityKind.PAYMENT && units.atomicUnits.sign <= 0)
            ) {
                error = "Use a positive quantity for deposits/rewards or a negative quantity for payments."
                return@Button
            }
            val fee =
                if (manualFee.isBlank()) {
                    null
                } else {
                    runCatching {
                        AtomicAmount.fromDecimal(asset, ExactDecimal.parse(manualFee), requireNotNull(decimals))
                    }.getOrNull()
                }
            if (manualFee.isNotBlank() && (fee == null || fee.atomicUnits.sign <= 0)) {
                error = "Enter a positive fee in $symbol or leave the fee empty."
                return@Button
            }
            val occurredAt =
                if (manualTime.isBlank()) {
                    Clock.System.now().toEpochMilliseconds()
                } else {
                    runCatching { Instant.parse(manualTime.trim()).toEpochMilliseconds() }.getOrNull()
                }
            if (occurredAt == null || occurredAt < 0) {
                error = "Enter a UTC date and time such as 2026-11-01T12:00:00Z."
                return@Button
            }
            execute("Manual activity recorded") {
                val id = ActivityId(uniqueId("manual"))
                app.transactions.saveManualActivity(
                    Activity(
                        id,
                        occurredAt,
                        id.value,
                        selectedKind,
                        ActivityStatus.CONFIRMED,
                        listOf(
                            Movement(
                                EventId("${id.value}:0"),
                                account!!,
                                units,
                                if (selectedKind ==
                                    ActivityKind.REWARD
                                ) {
                                    MovementRole.REWARD
                                } else {
                                    MovementRole.TRANSFER
                                },
                            ),
                        ) +
                            listOfNotNull(
                                fee?.let {
                                    Movement(
                                        EventId("${id.value}:fee"),
                                        account!!,
                                        AtomicAmount(asset, -it.atomicUnits),
                                        MovementRole.FEE,
                                    )
                                },
                            ),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                if (name.isNotBlank()) app.transactions.saveOverlay(ActivityOverlay(id, label = name.trim()))
            }
            close()
        }, modifier = Modifier.fillMaxWidth()) { Text("Record locally") }
    }
}
