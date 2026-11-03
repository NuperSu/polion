package io.polion.core.application

import io.polion.core.accounting.LedgerAccounting
import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.Rational
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class TransactionType { BUY, SELL, TRANSFER, DEPOSIT, WITHDRAWAL, REWARD, FEE }

/** Executed quantities are gross; fees are separate expenses, not capitalized into trade prices. */
data class TransactionDraft(
    val id: ActivityId,
    val type: TransactionType,
    val asset: AssetId,
    val account: AccountId,
    val quantity: ExactDecimal,
    val atMillis: Long,
    val quote: AssetId,
    val unitPrice: ExactDecimal? = null,
    val destination: AccountId? = null,
    val incomingExternal: Boolean = false,
    val counterparty: String? = null,
    val feeAsset: AssetId? = null,
    val feeQuantity: ExactDecimal? = null,
    val feeUnitPrice: ExactDecimal? = null,
    val notes: String = "",
) {
    internal fun encode(): String =
        JsonObject(
            mapOf(
                "id" to id.value,
                "type" to type.name,
                "asset" to asset.value,
                "account" to account.value,
                "quantity" to quantity.toString(),
                "at" to atMillis.toString(),
                "quote" to quote.value,
                "price" to unitPrice?.toString(),
                "destination" to destination?.value,
                "incoming" to incomingExternal.toString(),
                "counterparty" to counterparty,
                "feeAsset" to feeAsset?.value,
                "fee" to feeQuantity?.toString(),
                "feePrice" to feeUnitPrice?.toString(),
                "notes" to notes,
            ).filterValues { it != null }.mapValues { JsonPrimitive(it.value) },
        ).toString()

    companion object {
        internal fun decode(value: String): TransactionDraft {
            val fields = Json.parseToJsonElement(value).jsonObject

            fun text(key: String) = fields[key]?.jsonPrimitive?.content
            return TransactionDraft(
                ActivityId(requireNotNull(text("id"))),
                TransactionType.valueOf(requireNotNull(text("type"))),
                AssetId(requireNotNull(text("asset"))),
                AccountId(requireNotNull(text("account"))),
                ExactDecimal.parse(requireNotNull(text("quantity"))),
                requireNotNull(text("at")).toLong(),
                AssetId(requireNotNull(text("quote"))),
                text("price")?.let(ExactDecimal::parse),
                text("destination")?.let(::AccountId),
                text("incoming") == "true",
                text("counterparty"),
                text("feeAsset")?.let(::AssetId),
                text("fee")?.let(ExactDecimal::parse),
                text("feePrice")?.let(ExactDecimal::parse),
                text("notes").orEmpty(),
            )
        }
    }
}

data class PreparedTransaction(
    val activity: Activity,
    val treatments: List<Treatment>,
    val notes: String,
)

/** Pure validation and exact movement construction; no quotes or costs are inferred from current markets. */
object TransactionDrafts {
    fun prepare(
        draft: TransactionDraft,
        decimals: Map<AssetId, Int>,
    ): PreparedTransaction {
        require(draft.atMillis >= 0) { "Date must be on or after the Unix epoch" }
        require(draft.quantity.coefficient.sign > 0) { "Amount must be positive" }
        require(draft.notes.length <= 4000) { "Notes must be at most 4000 characters" }
        require(draft.counterparty == null || draft.counterparty.length <= 500) { "Counterparty is too long" }
        val trade = draft.type == TransactionType.BUY || draft.type == TransactionType.SELL
        require(
            draft.unitPrice == null ||
                draft.type in setOf(TransactionType.BUY, TransactionType.SELL, TransactionType.DEPOSIT, TransactionType.REWARD),
        ) { "This transaction type does not accept an execution price" }
        require(draft.feeUnitPrice == null || draft.feeUnitPrice.coefficient.sign > 0) { "Fee conversion price must be positive" }
        require(!trade || draft.asset != draft.quote) { "Choose different assets for the trading pair" }
        require(!trade || draft.unitPrice?.coefficient?.sign == 1) { "Executed unit price must be positive" }
        require(draft.type == TransactionType.TRANSFER || draft.destination == null) { "Destination is only supported for transfers" }
        require(draft.destination == null || draft.destination != draft.account) { "Transfer accounts must differ" }
        require(!draft.incomingExternal || draft.type == TransactionType.TRANSFER && draft.destination == null) {
            "External incoming transfer cannot have a tracked destination"
        }
        val movements = mutableListOf<Movement>()
        val treatments = mutableListOf<Treatment>()

        fun amount(
            asset: AssetId,
            quantity: ExactDecimal,
        ) = AtomicAmount.fromDecimal(
            asset,
            quantity,
            requireNotNull(decimals[asset]) {
                "Unknown asset precision"
            },
        )

        fun add(
            suffix: String,
            account: AccountId,
            value: AtomicAmount,
            role: MovementRole,
        ): EventId {
            val id = EventId("${draft.id.value}-$suffix")
            movements += Movement(id, account, value, role, draft.counterparty?.takeIf { it.isNotBlank() })
            return id
        }

        fun negative(value: AtomicAmount) = value.copy(atomicUnits = -value.atomicUnits)

        fun zero() = QuoteValue(draft.quote, Rational.ZERO)
        val quantity = amount(draft.asset, draft.quantity)
        when (draft.type) {
            TransactionType.BUY, TransactionType.SELL -> {
                val consideration = amount(draft.quote, draft.quantity * requireNotNull(draft.unitPrice))
                require(consideration.atomicUnits.sign > 0) { "Consideration must be positive" }
                val gross = QuoteValue(draft.quote, Rational.fromDecimal(draft.quantity * draft.unitPrice))
                val buy = draft.type == TransactionType.BUY
                val base = add("asset", draft.account, if (buy) quantity else negative(quantity), MovementRole.TRADE_LEG)
                val quote = add("quote", draft.account, if (buy) negative(consideration) else consideration, MovementRole.TRADE_LEG)
                treatments += if (buy) Treatment.Acquire(base, gross) else Treatment.Dispose(base, gross)
                treatments += if (buy) Treatment.Dispose(quote, gross) else Treatment.Acquire(quote, gross)
            }
            TransactionType.TRANSFER -> {
                if (draft.destination != null) {
                    val out = add("asset", draft.account, negative(quantity), MovementRole.TRANSFER)
                    val into = add("destination", draft.destination, quantity, MovementRole.TRANSFER)
                    treatments += Treatment.InternalTransfer(out, into)
                } else if (draft.incomingExternal) {
                    treatments += Treatment.Acquire(add("asset", draft.account, quantity, MovementRole.TRANSFER), null, opening = true)
                } else {
                    treatments += Treatment.Dispose(add("asset", draft.account, negative(quantity), MovementRole.TRANSFER), null)
                }
            }
            TransactionType.DEPOSIT, TransactionType.REWARD -> {
                val role = if (draft.type == TransactionType.REWARD) MovementRole.REWARD else MovementRole.TRANSFER
                val cost =
                    draft.unitPrice?.let {
                        require(it.coefficient.sign >= 0) { "Evidence price cannot be negative" }
                        QuoteValue(draft.quote, Rational.fromDecimal(draft.quantity * it))
                    }
                treatments += Treatment.Acquire(add("asset", draft.account, quantity, role), cost, opening = cost == null)
            }
            TransactionType.WITHDRAWAL, TransactionType.FEE -> {
                val fee = draft.type == TransactionType.FEE
                treatments +=
                    Treatment.Dispose(
                        add("asset", draft.account, negative(quantity), if (fee) MovementRole.FEE else MovementRole.TRANSFER),
                        if (fee) zero() else null,
                        fee,
                    )
            }
        }
        if (draft.feeQuantity != null) {
            require(draft.type != TransactionType.FEE) { "A fee entry cannot have an additional fee" }
            require(draft.feeQuantity.coefficient.sign > 0) { "Fee must be positive" }
            val asset = requireNotNull(draft.feeAsset) { "Select fee asset" }
            if (asset != draft.asset &&
                asset != draft.quote
            ) {
                require(draft.feeUnitPrice?.coefficient?.sign == 1) { "A third-asset fee needs an evidenced conversion price" }
            }
            val fee = add("fee", draft.account, negative(amount(asset, draft.feeQuantity)), MovementRole.FEE)
            treatments += Treatment.Dispose(fee, zero(), fee = true)
        } else {
            require(draft.feeAsset == null && draft.feeUnitPrice == null) { "Fee amount is required" }
        }
        val kind =
            when (draft.type) {
                TransactionType.BUY, TransactionType.SELL -> ActivityKind.TRADE
                TransactionType.TRANSFER -> ActivityKind.TRANSFER
                TransactionType.DEPOSIT -> ActivityKind.DEPOSIT
                TransactionType.REWARD -> ActivityKind.REWARD
                TransactionType.WITHDRAWAL, TransactionType.FEE -> ActivityKind.PAYMENT
            }
        val activity =
            Activity(draft.id, draft.atMillis, draft.id.value, kind, ActivityStatus.CONFIRMED, movements, emptySet(), null, manual = true)
        LedgerAccounting.actions(activity, treatments)
        return PreparedTransaction(activity, treatments, draft.notes)
    }
}
