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
 * Turns Binance's records into activities of one account. Binance keeps no balance log, so its records are the
 * ledger, and the balance check fills what they do not cover:
 *
 * - **Trades:** each fill is a trade: base and quote legs (sides from `isBuyer`) and the commission as a fee.
 * - **Converts** are trades; each **dust** conversion is one trade of its small balances into BNB, with Binance's
 *   charge as a fee.
 * - **Deposits** (credited) and **withdrawals** (completed) are transfers; a withdrawal's network fee is its own
 *   movement, and on-chain ones keep their transaction for matching with wallets.
 * - **Distributions, airdrops and Simple Earn rewards** are rewards; a distribution Binance marks as a debit is an
 *   outflow.
 * - **Pay** is a transfer in or out; **card buys** bring crypto in (the card is outside the account), card sales
 *   take it out; **P2P** buys bring crypto in, sales take it out with the commission as a fee.
 * - **Moves between Binance's wallets** (Spot, Funding, Earn, …) are not records: the account is every wallet
 *   together, and its balance counts them all.
 * - **Balance check:** each coin's running balance goes through the activities in order. Wherever it would fall
 *   below zero, the missing amount arrives 1 ms before (history Binance no longer keeps, or trades in pairs it no
 *   longer lists). Whatever then differs from Binance's balance is one adjustment after the latest record.
 */
object BinanceDecoder {
    const val VERSION = "binance-1"
    const val TRADES = "trades"
    const val DEPOSITS = "deposits"
    const val WITHDRAWALS = "withdrawals"
    const val CONVERTS = "converts"
    const val DUST = "dust"
    const val DIVIDENDS = "dividends"
    const val FLEXIBLE_REWARDS = "flexible-rewards"
    const val LOCKED_REWARDS = "locked-rewards"
    const val PAY = "pay"
    const val CARD_BUYS = "card-buys"
    const val CARD_SALES = "card-sales"
    const val P2P_BUYS = "p2p-buys"
    const val P2P_SALES = "p2p-sales"

    /** Binance's balances at one moment: each coin's total over every wallet, and the raw record that holds them. */
    data class Balances(
        val atMillis: Long,
        val totals: Map<String, ExactDecimal>,
        val source: SourceRecordId,
    )

    /** Coins the saved records move; trades name theirs through [pairs] (symbol → base and quote). */
    fun coins(
        rows: List<ExchangeRow>,
        pairs: Map<String, Pair<String, String>>,
    ): Set<String> {
        val fields = listOf("coin", "asset", "currency", "fromAsset", "toAsset", "commissionAsset", "cryptoCurrency", "targetAsset")
        return rows
            .flatMap { row ->
                val o = Binance.row(row.payload)
                val nested = (o["userAssetDribbletDetails"] as? JsonArray).orEmpty().map { it as JsonObject }
                val symbol = if (row.stream == TRADES) (o["symbol"] as? JsonPrimitive)?.content?.let { pairs[it] } else null
                (listOf(o) + nested).flatMap { item -> fields.mapNotNull { (item[it] as? JsonPrimitive)?.content } } +
                    listOfNotNull(symbol?.first, symbol?.second)
            }.filter { it.isNotBlank() }
            .toSet()
    }

    fun decode(
        account: AccountId,
        uid: String,
        rows: List<ExchangeRow>,
        assets: Map<String, AssetId>,
        pairs: Map<String, Pair<String, String>>,
        balances: Balances?,
        decimals: (AssetId) -> Int = { ExchangeAssets.DECIMALS },
    ): ExchangeDecoded {
        val ledger = ExchangeLedgerBuilder(ExchangeKind.BINANCE, account, uid, assets, decimals, VERSION)
        rows.forEach { row ->
            val o = Binance.row(row.payload)
            val source = setOf(SourceRecordId(row.source))
            when (row.stream) {
                TRADES -> {
                    val t = Binance.trade(o)
                    val (base, quote) = pairs[t.symbol] ?: return@forEach
                    ledger.build("trade:${t.symbol}:${t.id}", t.atMillis, source) {
                        add(base, if (t.isBuyer) t.qty else negate(t.qty), MovementRole.TRADE_LEG)
                        add(quote, if (t.isBuyer) negate(t.quoteQty) else t.quoteQty, MovementRole.TRADE_LEG)
                        if (t.commissionAsset.isNotBlank()) fee(t.commissionAsset, t.commission)
                        ActivityKind.TRADE
                    }
                }
                CONVERTS -> {
                    val c = Binance.convert(o)
                    if (!c.success) return@forEach
                    ledger.build("convert:${c.id}", c.atMillis, source) {
                        add(c.fromAsset, negate(c.fromAmount), MovementRole.TRADE_LEG)
                        add(c.toAsset, c.toAmount, MovementRole.TRADE_LEG)
                        ActivityKind.TRADE
                    }
                }
                DUST -> {
                    val dust = Binance.dust(o)
                    ledger.build("dust:${dust.id}", dust.atMillis, source) {
                        dust.parts.forEach { part ->
                            add(part.fromAsset, negate(part.amount), MovementRole.TRADE_LEG)
                            add(part.targetAsset, part.received + part.fee, MovementRole.TRADE_LEG)
                            fee(part.targetAsset, part.fee)
                        }
                        ActivityKind.TRADE
                    }
                }
                DEPOSITS -> {
                    val d = Binance.deposit(o)
                    if (!d.credited) return@forEach
                    val id =
                        ledger.build("deposit:${d.id}", d.atMillis, source) {
                            add(d.coin, d.amount, MovementRole.TRANSFER)
                            ActivityKind.DEPOSIT
                        }
                    if (id != null && !d.internal && d.txId.isNotBlank()) ledger.transfers[id] = ExchangeTransfer(d.network, d.txId)
                }
                WITHDRAWALS -> {
                    val w = Binance.withdrawal(o)
                    if (!w.done) return@forEach
                    val id =
                        ledger.build("withdrawal:${w.id}", w.atMillis, source) {
                            add(w.coin, negate(w.amount), MovementRole.TRANSFER)
                            fee(w.coin, w.fee)
                            ActivityKind.TRANSFER
                        }
                    if (id != null && !w.internal && w.txId.isNotBlank()) ledger.transfers[id] = ExchangeTransfer(w.network, w.txId)
                }
                DIVIDENDS -> {
                    val d = Binance.dividend(o)
                    val incoming = d.amount.coefficient.sign > 0
                    ledger.build("dividend:${d.id}", d.atMillis, source) {
                        add(d.asset, d.amount, if (incoming) MovementRole.REWARD else MovementRole.TRANSFER)
                        if (incoming) ActivityKind.REWARD else ActivityKind.OTHER
                    }
                }
                FLEXIBLE_REWARDS, LOCKED_REWARDS -> {
                    val r = Binance.reward(o, locked = row.stream == LOCKED_REWARDS)
                    ledger.build("reward:${row.key}", r.atMillis, source) {
                        add(r.asset, r.amount, MovementRole.REWARD)
                        ActivityKind.REWARD
                    }
                }
                PAY -> {
                    val p = Binance.pay(o)
                    ledger.build("pay:${p.id}", p.atMillis, source) {
                        add(p.currency, p.amount, MovementRole.TRANSFER)
                        ActivityKind.TRANSFER
                    }
                }
                CARD_BUYS, CARD_SALES -> {
                    val f = Binance.fiatPayment(o, buy = row.stream == CARD_BUYS)
                    if (!f.completed) return@forEach
                    ledger.build("card:${f.id}", f.atMillis, source) {
                        add(f.crypto, if (f.buy) f.cryptoAmount else negate(f.cryptoAmount), MovementRole.TRANSFER)
                        if (f.buy) ActivityKind.DEPOSIT else ActivityKind.PAYMENT
                    }
                }
                P2P_BUYS, P2P_SALES -> {
                    val p = Binance.p2p(o)
                    if (!p.completed) return@forEach
                    ledger.build("p2p:${p.id}", p.atMillis, source) {
                        add(p.asset, if (p.buy) p.amount else negate(p.amount), MovementRole.TRANSFER)
                        fee(p.asset, p.commission)
                        if (p.buy) ActivityKind.DEPOSIT else ActivityKind.PAYMENT
                    }
                }
            }
        }
        if (balances != null) settle(ledger, balances, rows.maxOfOrNull { it.atMillis })
        return ledger.result()
    }

    /**
     * Fills what the records do not explain: an inflow 1 ms before any moment a coin's running balance would fall
     * below zero, then the rest of the difference to Binance's balance after the latest record.
     */
    private fun settle(
        ledger: ExchangeLedgerBuilder,
        balances: Balances,
        latest: Long?,
    ) {
        val byCoin = ledger.changes.groupBy { it.coin }
        (byCoin.keys + balances.totals.keys).sorted().forEach { coin ->
            var running = ZERO
            val gaps = mutableListOf<Pair<Long, ExactDecimal>>()
            byCoin[coin]
                .orEmpty()
                .groupBy { it.order }
                .toSortedMap()
                .values
                .forEach { changes ->
                    running += changes.fold(ZERO) { sum, change -> sum + change.amount }
                    if (running.coefficient.sign < 0) {
                        gaps += (changes.first().atMillis - 1) to negate(running)
                        running = ZERO
                    }
                }
            gaps.forEach { (at, amount) ->
                ledger.build("gap:$coin:$at", at, setOf(balances.source)) {
                    add(coin, amount, MovementRole.TRANSFER)
                    null
                }
            }
            val rest = (balances.totals[coin] ?: ZERO) - running
            if (rest.coefficient.sign != 0 && coin !in ledger.unresolved) {
                ledger.build("balance:$coin", (latest ?: balances.atMillis) + 1, setOf(balances.source)) {
                    add(coin, rest, MovementRole.TRANSFER)
                    null
                }
            }
        }
    }

    private fun negate(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)

    private val ZERO = ExactDecimal.parse("0")
}
