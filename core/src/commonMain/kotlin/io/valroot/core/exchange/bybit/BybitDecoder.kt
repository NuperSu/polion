package io.valroot.core.exchange.bybit

import io.valroot.core.exchange.ExchangeAssets
import io.valroot.core.exchange.ExchangeDecoded
import io.valroot.core.exchange.ExchangeHistory
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeLedgerBuilder
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ExchangeSnapshot
import io.valroot.core.exchange.ExchangeStreamState
import io.valroot.core.exchange.ExchangeTransfer
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.SourceRecordId
import kotlinx.serialization.json.JsonPrimitive

/** Bybit's balances at one moment: the Funding and Unified wallets per coin, and every other account type's total. */
data class BybitBalances(
    val atMillis: Long,
    /** Wallet (`FUND` or `UNIFIED`) and coin → amount. */
    val wallets: Map<Pair<String, String>, ExactDecimal>,
    /** Coin → amount held in Earn, bots, copy trading, loans and other account types. */
    val elsewhere: Map<String, ExactDecimal>,
    /** The raw observation that holds these balances. */
    val source: SourceRecordId,
)

/**
 * Turns Bybit's records into activities of one account. The two balance logs are the ledger; the other records only
 * add detail:
 *
 * - **Unified Trading log:** rows of one spot trade (one per coin, sharing a trade ID) become a trade; conversions
 *   inside the wallet likewise. Each row's leg is its balance change before the fee, and the fee its own movement.
 *   Rewards (airdrops, bonuses, fee refunds, interest) are rewards. Moves into and out of the wallet within Bybit
 *   (from Funding, to Earn and other products) change nothing for the account and are left out. Everything else,
 *   such as derivatives settlement, funding or borrowing, is kept as it is.
 * - **Funding history:** deposits and withdrawals take the on-chain transaction from the matching deposit or
 *   withdrawal record, and a withdrawal's fee becomes its own movement; the two sides of a convert become one trade;
 *   interest and rewards are rewards; moves within Bybit are left out; other rows are kept as they are.
 * - **Opening balances:** each log records the balance after every row, so the balance before the first row is known
 *   exactly. Each wallet's opening balance per coin is the current balance less every change since, recorded just
 *   before the first row.
 * - **Remainder:** coins held where no history exists (Earn, bots, …) and anything the records do not explain are one
 *   adjustment per coin, just after the latest record.
 */
object BybitDecoder : ExchangeHistory {
    const val VERSION = "bybit-1"
    const val LOG = "log"
    const val FUNDING = "funding"
    const val DEPOSITS = "deposits"
    const val WITHDRAWALS = "withdrawals"
    const val CONVERTS = "converts"
    const val UNIFIED = "UNIFIED"
    const val FUND = "FUND"

    /** The two sides of a move between the Funding and Unified wallets are at most this far apart. */
    private const val PAIR_MILLIS = 10 * 60_000L

    /** Records may be dated up to this far from the log row they describe. */
    private const val MATCH_MILLIS = 6 * 3_600_000L

    /** Moves between the Unified wallet and the Funding wallet; internal when both sides are in the history. */
    private val WALLET_TRANSFER_TYPES = setOf("TRANSFER_IN", "TRANSFER_OUT")

    /** Moves between the Unified wallet and Bybit products (Earn, bonus funds and others), which have no history. */
    private val PRODUCT_LOG_TYPES =
        setOf(
            "TRANSFER_IN",
            "TRANSFER_OUT",
            "BONUS_TRANSFER_IN",
            "BONUS_TRANSFER_OUT",
            "FLEXIBLE_STAKING_SUBSCRIPTION",
            "FLEXIBLE_STAKING_REDEMPTION",
            "FIXED_STAKING_SUBSCRIPTION",
            "FLEXIBLE_STAKING_REFUND",
            "FIXED_STAKING_REFUND",
            "PEF_TRANSFER_IN",
            "PEF_TRANSFER_OUT",
            "ONCHAINEARN_SUBSCRIPTION",
            "ONCHAINEARN_REDEMPTION",
            "ONCHAINEARN_REFUND",
            "STRUCTURE_PRODUCT_SUBSCRIPTION",
            "STRUCTURE_PRODUCT_REFUND",
            "CLASSIC_WEALTH_MANAGEMENT_SUBSCRIPTION",
            "PREMIMUM_WEALTH_MANAGEMENT_SUBSCRIPTION",
            "PREMIMUM_WEALTH_MANAGEMENT_REFUND",
            "LIQUIDITY_MINING_SUBSCRIPTION",
            "LIQUIDITY_MINING_REFUND",
            "PWM_SUBSCRIPTION",
            "PWM_REFUND",
            "FIXED_SAVING_PRINCIPAL_REDEMPTION",
        )

    private val CONVERT_LOG_TYPES =
        setOf(
            "CURRENCY_BUY",
            "CURRENCY_SELL",
            "SPOT_REPAYMENT_BUY",
            "SPOT_REPAYMENT_SELL",
            "EARNING_REDEMPTION_BUY",
            "EARNING_REDEMPTION_SELL",
            "CONVERT",
            "BYUSDT_MINT",
            "BYUSDT_REDEMPTION",
        )

    private val REWARD_LOG_TYPES =
        setOf("AIRDROP", "BONUS", "FEE_REFUND", "BYUSDT_INTEREST", "PEF_PROFIT_SHARE", "STOCK_CORPORATE_ACTION_DIVIDEND")

    private val REWARD_WORDS =
        listOf("interest", "reward", "airdrop", "bonus", "distribution", "yield", "rebate", "commission", "cashback", "dividend")

    /** Earn moves of principal into and out of a product. */
    private val EARN_MOVES = listOf("subscription", "subscribe", "redemption", "redeem", "auto-earn", "auto-withdrawal", "unstake", "stake")

    /** What a Funding history row is, from Bybit's English business type and description. */
    private enum class FundingKind { DEPOSIT, WITHDRAWAL, CONVERT, INTERNAL, REWARD, PAYMENT, TRANSFER, OTHER }

    private fun classify(entry: BybitFundingEntry): FundingKind {
        val business = entry.business.lowercase()
        val description = entry.description.lowercase()
        val text = "$business $description"
        val incoming = entry.change.coefficient.sign > 0
        return when {
            "deposit" in business && incoming -> FundingKind.DEPOSIT
            "withdraw" in business && !incoming -> FundingKind.WITHDRAWAL
            "convert" in text || business == "exchange" -> FundingKind.CONVERT
            // Moves into and out of Earn and other products, which have no history of their own: their coins stay
            // in the account, held where the remainder counts them.
            business == "earn" && "dual asset" !in description && EARN_MOVES.any { it in description } -> FundingKind.TRANSFER
            incoming && REWARD_WORDS.any { it in text } -> FundingKind.REWARD
            "transfer" in text -> FundingKind.TRANSFER
            "card" in business && !incoming -> FundingKind.PAYMENT
            else -> FundingKind.OTHER
        }
    }

    fun decode(
        account: AccountId,
        uid: String,
        rows: List<ExchangeRow>,
        assets: Map<String, AssetId>,
        balances: BybitBalances?,
        /** Earliest moment each wallet's log covers, for coins it holds without any row. */
        coveredFrom: Map<String, Long> = emptyMap(),
        /** Decimals of each asset: 18 for exchange coins, 2 for currencies. */
        decimals: (AssetId) -> Int = { ExchangeAssets.DECIMALS },
    ): ExchangeDecoded {
        val context = ExchangeLedgerBuilder(ExchangeKind.BYBIT, account, uid, assets, decimals, VERSION)
        val byStream = rows.groupBy { it.stream }
        val logs = byStream[LOG].orEmpty().map { Bybit.logEntry(Bybit.row(it.payload)) to SourceRecordId(it.source) }
        val funding = byStream[FUNDING].orEmpty().map { Bybit.fundingEntry(Bybit.row(it.payload)) to SourceRecordId(it.source) }
        val deposits = byStream[DEPOSITS].orEmpty().map { Bybit.deposit(Bybit.row(it.payload)) to SourceRecordId(it.source) }
        val withdrawals = byStream[WITHDRAWALS].orEmpty().map { Bybit.withdrawal(Bybit.row(it.payload)) to SourceRecordId(it.source) }
        val converts = byStream[CONVERTS].orEmpty().map { Bybit.convert(Bybit.row(it.payload)) to SourceRecordId(it.source) }

        val paired = pairWalletTransfers(logs.map { it.first }, funding.map { it.first })
        decodeLog(context, logs, paired)
        decodeFunding(context, funding, deposits, withdrawals, converts, paired)
        if (balances != null) settle(context, logs, funding, balances, coveredFrom, rows.maxOfOrNull { it.atMillis })
        return context.result()
    }

    /**
     * Unified log transfers and Funding rows that are the two sides of one move between the wallets: the same coin and
     * opposite amounts, at most [PAIR_MILLIS] apart, nearest first. Returns the paired log keys and Funding row IDs.
     */
    private fun pairWalletTransfers(
        logs: List<BybitLogEntry>,
        funding: List<BybitFundingEntry>,
    ): Set<String> {
        val candidates = funding.filter { "unified trading account" in it.description.lowercase() }.toMutableList()
        val paired = mutableSetOf<String>()
        logs.filter { it.type in WALLET_TRANSFER_TYPES }.sortedBy { it.atMillis }.forEach { entry ->
            val match =
                candidates
                    .filter { it.currency == entry.currency && (it.change + entry.change).coefficient.sign == 0 }
                    .minByOrNull { kotlin.math.abs(it.atMillis - entry.atMillis) }
                    ?.takeIf { kotlin.math.abs(it.atMillis - entry.atMillis) <= PAIR_MILLIS }
            if (match != null) {
                candidates -= match
                paired += "log:" + entry.key
                paired += "funding:" + match.id
            }
        }
        return paired
    }

    private fun decodeLog(
        context: ExchangeLedgerBuilder,
        logs: List<Pair<BybitLogEntry, SourceRecordId>>,
        paired: Set<String>,
    ) {
        val groups = linkedMapOf<String, MutableList<Pair<BybitLogEntry, SourceRecordId>>>()
        logs.forEach { (entry, source) ->
            if ("log:" + entry.key in paired) return@forEach
            val group =
                when {
                    entry.type == "TRADE" && entry.category == "spot" -> "trade:" + entry.tradeId.ifBlank { entry.key }
                    entry.type in CONVERT_LOG_TYPES -> "convert:" + entry.orderId.ifBlank { entry.atMillis.toString() }
                    else -> "log:" + entry.key
                }
            groups.getOrPut(group) { mutableListOf() } += entry to source
        }
        groups.forEach { (group, entries) ->
            val first = entries.first().first
            val reward = entries.size == 1 && first.type in REWARD_LOG_TYPES && first.change.coefficient.sign > 0
            context.build(group, entries.minOf { it.first.atMillis }, entries.map { it.second }.toSet()) {
                entries.forEach { (entry, _) ->
                    val leg = entry.change + entry.fee
                    add(
                        entry.currency,
                        leg,
                        when {
                            reward -> MovementRole.REWARD
                            group.startsWith("log:") -> MovementRole.TRANSFER
                            else -> MovementRole.TRADE_LEG
                        },
                    )
                    fee(entry.currency, entry.fee)
                }
                when {
                    reward -> ActivityKind.REWARD
                    first.type in WALLET_TRANSFER_TYPES || first.type in PRODUCT_LOG_TYPES -> ActivityKind.TRANSFER
                    else -> null
                }
            }
        }
    }

    private fun decodeFunding(
        context: ExchangeLedgerBuilder,
        funding: List<Pair<BybitFundingEntry, SourceRecordId>>,
        deposits: List<Pair<BybitDeposit, SourceRecordId>>,
        withdrawals: List<Pair<BybitWithdrawal, SourceRecordId>>,
        converts: List<Pair<BybitConvert, SourceRecordId>>,
        paired: Set<String>,
    ) {
        val usedDeposits = mutableSetOf<String>()
        val usedWithdrawals = mutableSetOf<String>()
        val convertRows = mutableMapOf<String, MutableList<Pair<BybitFundingEntry, SourceRecordId>>>()
        val doneConverts = converts.filter { it.first.status == "success" }
        funding.forEach { (entry, source) ->
            val incoming = entry.change.coefficient.sign > 0
            when (if ("funding:" + entry.id in paired) FundingKind.INTERNAL else classify(entry)) {
                FundingKind.DEPOSIT -> {
                    val record =
                        deposits
                            .filter { (d, _) ->
                                d.credited &&
                                    d.id !in usedDeposits &&
                                    d.coin == entry.currency &&
                                    d.amount.compareTo(entry.change) == 0
                            }.minByOrNull { kotlin.math.abs(it.first.atMillis - entry.atMillis) }
                            ?.takeIf { kotlin.math.abs(it.first.atMillis - entry.atMillis) <= MATCH_MILLIS }
                    record?.let { usedDeposits += it.first.id }
                    val id =
                        context.build("funding:${entry.id}", entry.atMillis, setOfNotNull(source, record?.second)) {
                            add(entry.currency, entry.change, MovementRole.TRANSFER)
                            ActivityKind.DEPOSIT
                        }
                    if (id != null && record != null && record.first.txId.isNotBlank()) {
                        context.transfers[id] = ExchangeTransfer(record.first.chain, record.first.txId)
                    }
                }
                FundingKind.WITHDRAWAL -> {
                    val paid = ExactDecimal(-entry.change.coefficient, entry.change.scale)
                    val record =
                        withdrawals
                            .filter { (w, _) ->
                                !w.failed &&
                                    w.id !in usedWithdrawals &&
                                    w.coin == entry.currency &&
                                    ((w.amount + w.fee).compareTo(paid) == 0 || w.amount.compareTo(paid) == 0)
                            }.minByOrNull { kotlin.math.abs(it.first.atMillis - entry.atMillis) }
                            ?.takeIf { kotlin.math.abs(it.first.atMillis - entry.atMillis) <= MATCH_MILLIS }
                    record?.let { usedWithdrawals += it.first.id }
                    val feeRecord = record?.first?.takeIf { (it.amount + it.fee).compareTo(paid) == 0 && it.fee.coefficient.sign != 0 }
                    val id =
                        context.build("funding:${entry.id}", entry.atMillis, setOfNotNull(source, record?.second)) {
                            if (feeRecord != null) {
                                add(entry.currency, negate(feeRecord.amount), MovementRole.TRANSFER)
                                fee(entry.currency, feeRecord.fee)
                            } else {
                                add(entry.currency, entry.change, MovementRole.TRANSFER)
                            }
                            ActivityKind.TRANSFER
                        }
                    if (id != null && record != null && record.first.txId.isNotBlank() && record.first.onChain) {
                        context.transfers[id] = ExchangeTransfer(record.first.chain, record.first.txId)
                    }
                }
                FundingKind.CONVERT -> {
                    val record =
                        doneConverts
                            .filter { (c, _) ->
                                kotlin.math.abs(c.atMillis - entry.atMillis) <= MATCH_MILLIS &&
                                    (
                                        (!incoming && c.fromCoin == entry.currency && c.fromAmount.compareTo(negate(entry.change)) == 0) ||
                                            (incoming && c.toCoin == entry.currency && c.toAmount.compareTo(entry.change) == 0)
                                    )
                            }.minByOrNull { kotlin.math.abs(it.first.atMillis - entry.atMillis) }
                    if (record != null) {
                        convertRows.getOrPut(record.first.id) { mutableListOf() } += entry to source
                    } else {
                        single(context, entry, source, null)
                    }
                }
                FundingKind.INTERNAL -> Unit
                FundingKind.REWARD -> single(context, entry, source, ActivityKind.REWARD)
                FundingKind.PAYMENT -> single(context, entry, source, ActivityKind.PAYMENT)
                FundingKind.TRANSFER -> single(context, entry, source, ActivityKind.TRANSFER)
                FundingKind.OTHER -> single(context, entry, source, null)
            }
        }
        convertRows.forEach { (convertId, entries) ->
            val record = doneConverts.first { it.first.id == convertId }
            context.build("convert:$convertId", entries.minOf { it.first.atMillis }, entries.map { it.second }.toSet() + record.second) {
                entries.forEach { (entry, _) -> add(entry.currency, entry.change, MovementRole.TRADE_LEG) }
                null
            }
        }
    }

    private fun single(
        context: ExchangeLedgerBuilder,
        entry: BybitFundingEntry,
        source: SourceRecordId,
        kind: ActivityKind?,
    ) {
        context.build("funding:${entry.id}", entry.atMillis, setOf(source)) {
            add(entry.currency, entry.change, if (kind == ActivityKind.REWARD) MovementRole.REWARD else MovementRole.TRANSFER)
            kind
        }
    }

    private fun settle(
        context: ExchangeLedgerBuilder,
        logs: List<Pair<BybitLogEntry, SourceRecordId>>,
        funding: List<Pair<BybitFundingEntry, SourceRecordId>>,
        balances: BybitBalances,
        coveredFrom: Map<String, Long>,
        latest: Long?,
    ) {
        val changes =
            logs.map { (entry, _) -> Triple(UNIFIED, entry.currency, entry.change to entry.atMillis) } +
                funding.map { (entry, _) -> Triple(FUND, entry.currency, entry.change to entry.atMillis) }
        val byWallet = changes.filter { it.third.second <= balances.atMillis }.groupBy { it.first to it.second }
        (balances.wallets.keys + byWallet.keys).forEach { key ->
            val (wallet, coin) = key
            val rows = byWallet[key].orEmpty()
            val opening = (balances.wallets[key] ?: ZERO) - rows.fold(ZERO) { sum, row -> sum + row.third.first }
            if (opening.coefficient.sign == 0) return@forEach
            val at = (rows.minOfOrNull { it.third.second } ?: coveredFrom[wallet] ?: balances.atMillis) - 1
            context.build("opening:$wallet:$coin", at, setOf(balances.source)) {
                add(coin, opening, MovementRole.TRANSFER)
                null
            }
        }
        val held = mutableMapOf<String, ExactDecimal>()
        balances.wallets.forEach { (key, amount) -> held[key.second] = (held[key.second] ?: ZERO) + amount }
        balances.elsewhere.forEach { (coin, amount) -> held[coin] = (held[coin] ?: ZERO) + amount }
        val recorded = mutableMapOf<String, ExactDecimal>()
        context.totals.forEach { (coin, amount) -> recorded[coin] = amount }
        (held.keys + recorded.keys).forEach { coin ->
            if (coin in context.unresolved) return@forEach
            val rest = (held[coin] ?: ZERO) - (recorded[coin] ?: ZERO)
            if (rest.coefficient.sign == 0) return@forEach
            context.build("balance:$coin", (latest ?: balances.atMillis) + 1, setOf(balances.source)) {
                add(coin, rest, MovementRole.TRANSFER)
                null
            }
        }
    }

    private fun negate(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)

    private val ZERO = ExactDecimal.parse("0")

    override val version: String get() = VERSION

    override fun coins(
        rows: List<ExchangeRow>,
        pairs: Map<String, Pair<String, String>>,
    ): Set<String> =
        rows
            .flatMap { row ->
                val o = Bybit.row(row.payload)
                listOf("currency", "coin", "fromCoin", "toCoin").mapNotNull { (o[it] as? JsonPrimitive)?.content }
            }.filter { it.isNotBlank() }
            .toSet()

    override fun balanceRecord(snapshot: ExchangeSnapshot): String =
        "bybit-balances-1\n" +
            snapshot.held
                .sortedWith(compareBy({ it.wallet }, { it.coin }))
                .joinToString("\n") { "${it.wallet}|${it.coin}|${it.amount}" }

    override fun recordsFrom(
        streams: Map<String, ExchangeStreamState>,
        snapshot: ExchangeSnapshot,
    ): Long? = snapshot.coveredFrom.values.minOrNull()

    /** Funding and Unified wallets are checked per coin against their logs; every other account type is a total. */
    override fun decode(
        account: AccountId,
        uid: String,
        rows: List<ExchangeRow>,
        assets: Map<String, AssetId>,
        snapshot: ExchangeSnapshot,
        source: SourceRecordId,
        recordsFrom: Long?,
        decimals: (AssetId) -> Int,
    ): ExchangeDecoded {
        val wallets = snapshot.held.filter { it.wallet == UNIFIED || it.wallet == FUND }
        val elsewhere = snapshot.held - wallets.toSet()
        val balances =
            BybitBalances(
                snapshot.atMillis,
                wallets.associate { (it.wallet to it.coin) to it.amount },
                elsewhere.groupBy { it.coin }.mapValues { (_, list) -> list.map { it.amount }.reduce { a, b -> a + b } },
                source,
            )
        return decode(account, uid, rows, assets, balances, snapshot.coveredFrom, decimals)
    }
}
