package io.valroot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.application.TransactionDraft
import io.valroot.core.application.TransactionDrafts
import io.valroot.core.application.TransactionType
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.ui.model.ModelAssetOption
import io.valroot.ui.model.ModelDraftField
import io.valroot.ui.model.ModelTransactionDialog
import io.valroot.ui.model.ModelTransactionDialogActions
import io.valroot.ui.model.ModelTransactionDialogState
import io.valroot.ui.model.ModelTransactionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

@Composable
internal fun TransactionChoice(
    label: String,
    choices: List<Pair<String, String>>,
    selected: String,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("transaction-choice-$label")) {
            Text("$label: ${choices.find { it.first == selected }?.second ?: "Select"} ▾")
        }
        DropdownMenu(open, { open = false }) {
            choices.forEach { (id, name) ->
                DropdownMenuItem({ Text(name) }, {
                    change(id)
                    open = false
                })
            }
        }
    }
}

@Composable
internal fun TransactionEntry(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    manual: Boolean,
    initialAsset: AssetId?,
    initialDraft: TransactionDraft?,
    onManual: () -> Unit,
    onClose: () -> Unit,
    onWallet: () -> Unit,
    onImport: () -> Unit,
    onSaved: () -> Unit,
    onAccountCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val assets = (data.valuations + data.marketCoins.map { it.detailValuation() }).distinctBy { it.asset }
    val options =
        (
            assets.map { ModelAssetOption(it.asset.value, it.metadata?.symbol ?: it.asset.value, it.metadata?.name ?: it.asset.value) } +
                listOf(ModelAssetOption(io.valroot.core.bitcoin.BITCOIN_ASSET.value, "BTC", "Bitcoin")) +
                listOf("USD", "EUR", "GBP").map { ModelAssetOption(it, it, it) }
        ).distinctBy { it.id }
    val id by rememberSaveable { mutableStateOf(initialDraft?.id?.value ?: transactionIdentity()) }
    var selected by rememberSaveable {
        mutableStateOf(
            initialDraft?.asset?.value ?: initialAsset?.value ?: options.firstOrNull()?.id.orEmpty(),
        )
    }

    fun ticker(id: String) = options.find { it.id == id }?.ticker ?: id
    var type by rememberSaveable { mutableStateOf(initialDraft?.type?.name ?: "BUY") }
    var date by rememberSaveable {
        mutableStateOf(Instant.fromEpochMilliseconds(initialDraft?.atMillis ?: app.analytics.evaluationTimeMillis()).toString())
    }
    var price by rememberSaveable { mutableStateOf(initialDraft?.unitPrice?.toString().orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(initialDraft?.quantity?.toString().orEmpty()) }
    var fee by rememberSaveable { mutableStateOf(initialDraft?.feeQuantity?.toString().orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(initialDraft?.notes.orEmpty()) }
    var quote by rememberSaveable { mutableStateOf(initialDraft?.quote?.value ?: data.currency.value) }
    var account by rememberSaveable {
        mutableStateOf(
            initialDraft?.account?.value
                ?: data.balances.quantities.keys
                    .firstOrNull()
                    ?.account
                    ?.value
                ?: data.ownership.accounts
                    .firstOrNull { it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags }
                    ?.id
                    ?.value
                    .orEmpty(),
        )
    }
    var destination by rememberSaveable { mutableStateOf(initialDraft?.destination?.value.orEmpty()) }
    var incoming by rememberSaveable { mutableStateOf(initialDraft?.incomingExternal ?: false) }
    var counterparty by rememberSaveable { mutableStateOf(initialDraft?.counterparty.orEmpty()) }
    var feeAsset by rememberSaveable { mutableStateOf(initialDraft?.feeAsset?.value ?: quote) }
    var feePrice by rememberSaveable { mutableStateOf(initialDraft?.feeUnitPrice?.toString().orEmpty()) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var newAccounts by remember { mutableStateOf(emptyList<Account>()) }
    var owner by remember {
        mutableStateOf(
            data.ownership.owners
                .firstOrNull()
                ?.id,
        )
    }
    var saving by remember { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val accounts =
        (data.ownership.accounts + newAccounts)
            .filter {
                it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags
            }.distinctBy { it.id }

    fun draft(): TransactionDraft =
        TransactionDraft(
            ActivityId(id),
            TransactionType.valueOf(type),
            AssetId(selected),
            AccountId(account),
            ExactDecimal.parse(amount.trim()),
            Instant.parse(date.trim()).toEpochMilliseconds(),
            AssetId(quote),
            price
                .takeIf {
                    type in listOf("BUY", "SELL", "DEPOSIT", "REWARD") &&
                        it.isNotBlank()
                }?.let { ExactDecimal.parse(it.trim()) },
            destination.takeIf { type == "TRANSFER" && it.isNotBlank() }?.let(::AccountId),
            type == "TRANSFER" && incoming,
            counterparty.takeIf { it.isNotBlank() },
            feeAsset.takeIf { fee.isNotBlank() }?.let(::AssetId),
            fee
                .takeIf {
                    it.isNotBlank()
                }?.let {
                    ExactDecimal.parse(it.trim())
                },
            feePrice.takeIf { fee.isNotBlank() && it.isNotBlank() }?.let { ExactDecimal.parse(it.trim()) },
            notes,
        )
    val scales =
        assets.mapNotNull { a -> a.metadata?.let { a.asset to it.decimals } }.toMap() + mapOf(io.valroot.core.bitcoin.BITCOIN_ASSET to 8) +
            listOf("USD", "EUR", "GBP").associate { AssetId(it) to 2 }

    fun validate(): TransactionDraft {
        val draft = draft()
        require(accounts.any { it.id == draft.account }) { "Select an active account" }
        require(draft.destination == null || accounts.any { it.id == draft.destination }) { "Select an active destination" }
        require(
            draft.destination == null ||
                accounts.find { it.id == draft.destination }?.ownerId == accounts.find { it.id == draft.account }?.ownerId,
        ) { "Internal transfers require the same owner" }
        TransactionDrafts.prepare(draft, scales)
        return draft
    }
    // A trade pairs two different assets; the same currency cannot be both the traded asset and the quote.
    val trade = type == "BUY" || type == "SELL"
    val assetChoices = if (trade) options.filter { it.id != quote } else options
    val quoteChoices = if (trade) options.filter { it.id != selected } else options
    ModelTransactionDialog(
        ModelTransactionDialogState(
            manual,
            assetChoices,
            selected,
            ModelTransactionType.valueOf(type),
            "Local manual account",
            "${ticker(selected)}/${ticker(quote)}",
            date,
            price,
            amount,
            fee,
            notes,
            canSave =
                !saving &&
                    runCatching {
                        validate()
                    }.isSuccess,
            saving = saving,
            types = ModelTransactionType.entries,
            showPrice =
                type in listOf("BUY", "SELL", "DEPOSIT", "REWARD"),
            showFee = type != "FEE",
            message =
                if (!manual) {
                    null
                } else {
                    error
                        ?: runCatching {
                            validate()
                        }.exceptionOrNull()?.let {
                            if (amount.isBlank()) "Enter an amount and select the transaction accounts." else it.message
                        }
                },
            saveLabel = "Review transaction",
            priceLabel = "Executed / evidence price ($quote); optional outside trades",
            feeLabel = "Fee amount (${ticker(feeAsset)})",
            canImport = true,
        ),
        ModelTransactionDialogActions(
            { if (!saving) onClose() },
            onWallet,
            onManual,
            onImport,
            { selected = it },
            {
                type = it.name
                destination = ""
                incoming = false
            },
            { field, value ->
                error = null
                when (field) {
                    ModelDraftField.DATE -> date = value
                    ModelDraftField.PRICE -> price = value
                    ModelDraftField.AMOUNT -> amount = value
                    ModelDraftField.FEE -> fee = value
                    ModelDraftField.NOTES -> notes = value
                }
            },
            {
                try {
                    validate()
                    error = null
                    review = true
                } catch (
                    e: Exception,
                ) {
                    error = e.message ?: "Check the transaction fields"
                }
            },
        ),
    ) {
        TransactionChoice("Account", accounts.map { it.id.value to it.label }, account) { account = it }
        OutlinedTextField(accountName, { accountName = it }, label = { Text("New local account name") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = accountName.isNotBlank() && !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    val value =
                        withContext(host.dispatcher) {
                            val ownerId = owner ?: OwnerId(transactionIdentity()).also { app.accounts.saveOwner(Owner(it, "Local owner")) }
                            owner = ownerId
                            Account(
                                AccountId(transactionIdentity()),
                                ownerId,
                                accountName.trim(),
                                AccountKind.ACCOUNT,
                            ).also { app.accounts.saveAccount(it) }
                        }
                    newAccounts = newAccounts + value
                    account = value.id.value
                    accountName = ""
                    onAccountCreated()
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (e: Exception) {
                    error = e.message ?: "Could not create account"
                } finally {
                    saving = false
                }
            }
        }) { Text("Create local account") }
        TransactionChoice("Quote asset", quoteChoices.map { it.id to it.ticker }, quote) { quote = it }
        if (type == "TRANSFER") {
            TransactionChoice("Destination", listOf("" to "External / unknown") + accounts.map { it.id.value to it.label }, destination) {
                destination =
                    it
                ; incoming = false
            }
            if (destination.isEmpty()) {
                TransactionChoice("Direction", listOf("out" to "To external", "in" to "From external"), if (incoming) "in" else "out") {
                    incoming =
                        it == "in"
                }
            }
        }
        OutlinedTextField(counterparty, {
            counterparty = it
        }, label = { Text("External counterparty (optional)") }, modifier = Modifier.fillMaxWidth())
        if (type != "FEE") TransactionChoice("Fee asset", options.map { it.id to it.ticker }, feeAsset) { feeAsset = it }
        if (fee.isNotBlank() &&
            feeAsset != selected &&
            feeAsset != quote
        ) {
            OutlinedTextField(
                feePrice,
                { feePrice = it },
                label = { Text("Evidenced fee unit price ($quote)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (review) {
        val prepared = runCatching { TransactionDrafts.prepare(validate(), scales) }.getOrNull()
        AlertDialog(onDismissRequest = {
            if (!saving) review = false
        }, title = { Text("Review transaction", Modifier.testTag("transaction-review-title")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("$type · $date")
                prepared?.activity?.movements?.forEach { m ->
                    val precision = scales[m.amount.assetId] ?: 0
                    Text(
                        "${m.amount.decimal(
                            precision,
                        )} ${m.amount.assetId.value} · ${accounts.find { it.id == m.accountId }?.label} · ${m.role.name}",
                    )
                }
                Text(
                    "Fees are separate expenses. Trade averages use gross executed quantities and exclude fees. External acquisition costs stay unknown without evidence.",
                )
                Text(notes)
                error?.let { Text(it) }
            }
        }, confirmButton = {
            TextButton(enabled = prepared != null && !saving, modifier = Modifier.testTag("transaction-confirm"), onClick = {
                val value = validate()
                saving = true
                scope.launch {
                    try {
                        withContext(host.dispatcher) {
                            val expected = TransactionDrafts.prepare(value, scales)
                            // A recreated form may retry after the commit succeeded but its UI callback was cancelled.
                            val recorded =
                                initialDraft == null &&
                                    app.transactions.draft(value.id) == value &&
                                    app.accounting.effectiveActivity(value.id)?.activity == expected.activity &&
                                    app.accounting.treatments(value.id) == expected.treatments
                            if (!recorded) app.transactions.saveTransaction(value, editing = initialDraft != null)
                        }
                        onSaved()
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (
                        e: Exception,
                    ) {
                        error = e.message ?: "Could not save; your draft is retained."
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "Saving…" else "Save transaction") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { review = false }) { Text("Edit draft") } })
    }
}

private fun transactionIdentity() =
    "manual-${kotlin.random.Random.nextLong().toString().removePrefix("-")}-${kotlin.random.Random.nextLong().toString().removePrefix("-")}"
