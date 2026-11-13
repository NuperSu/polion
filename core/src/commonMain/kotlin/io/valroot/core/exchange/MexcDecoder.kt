package io.valroot.core.exchange

import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.SourceRecordId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns MEXC's records into activities of one account (its spot wallet):
 *
 * - **Trades:** each fill is a trade of base and quote, with the commission as a fee.
 * - **Dust** conversions are trades of small balances into MX, with MEXC's fee.
 * - **Deposits** (successful) and **withdrawals** (successful) are transfers; a withdrawal's fee is its own movement,
 *   and on-chain ones keep their transaction for matching with wallets.
 * - **Transfers with other MEXC users** are transfers in or out; the account's own identifier is its user ID, or the
 *   email or phone that every such transfer shares (with a single transfer, only the user ID tells).
 * - **Spot↔futures transfers** are transfers out and in: futures balances are not read.
 * - **Balance check:** MEXC keeps a month of trades and 90 days of transfers, so most of an older account's coins are
 *   explained by nothing: they are an opening amount at the start of the records, and gaps fill moments a coin would
 *   go below zero ([ExchangeLedgerBuilder.settleByRunningBalance]).
 */
object MexcDecoder {
    const val VERSION = "mexc-1"
    const val TRADES = "trades"
    const val DEPOSITS = "deposits"
    const val WITHDRAWALS = "withdrawals"
    const val INTERNAL = "internal-transfers"
    const val TO_FUTURES = "to-futures"
    const val FROM_FUTURES = "from-futures"
    const val DUST = "dust"

    /** MEXC's spot balances at one moment, the raw record holding them, and where the records start. */
    data class Balances(
        val atMillis: Long,
        val totals: Map<String, ExactDecimal>,
        val source: SourceRecordId,
        val recordsFrom: Long?,
    )

    /** Coins the saved records move; trades name theirs through [pairs] (symbol → base and quote). */
    fun coins(
        rows: List<ExchangeRow>,
        pairs: Map<String, Pair<String, String>>,
    ): Set<String> =
        rows
            .flatMap { row ->
                val o = Mexc.row(row.payload)
                val nested = (o["convertDetails"] as? JsonArray).orEmpty().map { it as JsonObject }
                val symbol = if (row.stream == TRADES) (o["symbol"] as? JsonPrimitive)?.content?.let { pairs[it] } else null
                (listOf(o) + nested).flatMap { item ->
                    listOf("coin", "asset", "commissionAsset").mapNotNull { (item[it] as? JsonPrimitive)?.content }
                } +
                    listOfNotNull(symbol?.first, symbol?.second) +
                    if (row.stream == DUST) listOf(DUST_TARGET) else emptyList()
            }.filter { it.isNotBlank() }
            .toSet()

    /** Dust becomes MX. */
    const val DUST_TARGET = "MX"

    fun decode(
        account: AccountId,
        uid: String,
        rows: List<ExchangeRow>,
        assets: Map<String, AssetId>,
        pairs: Map<String, Pair<String, String>>,
        balances: Balances?,
        decimals: (AssetId) -> Int = { ExchangeAssets.DECIMALS },
    ): ExchangeDecoded {
        val ledger = ExchangeLedgerBuilder(ExchangeKind.MEXC, account, uid, assets, decimals, VERSION)
        val internal = rows.filter { it.stream == INTERNAL }.map { Mexc.internalTransfer(Mexc.row(it.payload)) }
        val own = ownIdentifiers(uid, internal)
        rows.forEach { row ->
            val o = Mexc.row(row.payload)
            val source = setOf(SourceRecordId(row.source))
            when (row.stream) {
                TRADES -> {
                    val t = Mexc.trade(o)
                    val (base, quote) = pairs[t.symbol] ?: return@forEach
                    ledger.build("trade:${t.symbol}:${t.id}", t.atMillis, source) {
                        add(base, if (t.isBuyer) t.qty else negate(t.qty), MovementRole.TRADE_LEG)
                        add(quote, if (t.isBuyer) negate(t.quoteQty) else t.quoteQty, MovementRole.TRADE_LEG)
                        if (t.commissionAsset.isNotBlank()) fee(t.commissionAsset, t.commission)
                        ActivityKind.TRADE
                    }
                }
                DUST -> {
                    val dust = Mexc.dust(o)
                    ledger.build("dust:${row.key}", dust.atMillis, source) {
                        dust.parts.forEach { part ->
                            add(part.asset, negate(part.amount), MovementRole.TRADE_LEG)
                            add(DUST_TARGET, part.received + part.fee, MovementRole.TRADE_LEG)
                            fee(DUST_TARGET, part.fee)
                        }
                        ActivityKind.TRADE
                    }
                }
                DEPOSITS -> {
                    val d = Mexc.deposit(o)
                    if (!d.credited) return@forEach
                    val id =
                        ledger.build("deposit:${row.key}", d.atMillis, source) {
                            add(d.coin, d.amount, MovementRole.TRANSFER)
                            ActivityKind.DEPOSIT
                        }
                    if (id != null && d.txId.isNotBlank()) ledger.transfers[id] = ExchangeTransfer(d.network, d.txId)
                }
                WITHDRAWALS -> {
                    val w = Mexc.withdrawal(o)
                    if (!w.done) return@forEach
                    val id =
                        ledger.build("withdrawal:${w.id}", w.atMillis, source) {
                            add(w.coin, negate(w.amount), MovementRole.TRANSFER)
                            fee(w.coin, w.fee)
                            ActivityKind.TRANSFER
                        }
                    if (id != null && !w.internal && w.txId.isNotBlank()) ledger.transfers[id] = ExchangeTransfer(w.network, w.txId)
                }
                INTERNAL -> {
                    val t = Mexc.internalTransfer(o)
                    if (!t.success) return@forEach
                    val incoming = t.toAccount in own
                    ledger.build("internal:${t.id}", t.atMillis, source) {
                        add(t.asset, if (incoming) t.amount else negate(t.amount), MovementRole.TRANSFER)
                        ActivityKind.TRANSFER
                    }
                }
                TO_FUTURES, FROM_FUTURES -> {
                    val t = Mexc.universalTransfer(o)
                    if (!t.success) return@forEach
                    ledger.build("futures:${t.id}", t.atMillis, source) {
                        add(t.asset, if (t.fromSpot) negate(t.amount) else t.amount, MovementRole.TRANSFER)
                        ActivityKind.TRANSFER
                    }
                }
            }
        }
        if (balances != null) {
            ledger.settleByRunningBalance(
                balances.totals,
                balances.source,
                balances.atMillis,
                rows.maxOfOrNull { it.atMillis },
                balances.recordsFrom,
            )
        }
        return ledger.result()
    }

    /** The account's own identifiers: its user ID, and whatever email or phone appears on every transfer. */
    private fun ownIdentifiers(
        uid: String,
        transfers: List<MexcInternalTransfer>,
    ): Set<String> {
        if (transfers.size < 2) return setOf(uid)
        val shared =
            transfers
                .map { setOf(it.fromAccount, it.toAccount).filter(String::isNotBlank).toSet() }
                .reduceOrNull { a, b -> a intersect b }
                .orEmpty()
        return shared + uid
    }

    private fun negate(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)
}
