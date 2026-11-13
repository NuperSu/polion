package io.valroot.core.exchange.binance

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.exchange.ExchangeBalance
import io.valroot.core.exchange.ExchangeConnector
import io.valroot.core.exchange.ExchangeHistory
import io.valroot.core.exchange.ExchangeKeyInfo
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeProvider
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ExchangeSnapshot
import io.valroot.core.exchange.ExchangeTransport
import io.valroot.core.exchange.RequestBudget
import io.valroot.core.exchange.StreamWindows
import io.valroot.core.exchange.TradeSymbols
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactDecimal

/** Binance accounts through a read-only API key: balances, trades and every other record Binance keeps. */
val BINANCE_MANIFEST =
    IntegrationManifest(
        "binance-api",
        setOf(ExchangeKind.BINANCE.network),
        setOf("uid"),
        setOf(Capability.BALANCE, Capability.HISTORY, Capability.DECODING),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/** One windowed history stream: its window, how far back it goes, and when reading back may stop early. */
private data class BinanceStream(
    val name: String,
    val windowMillis: Long,
    /** Earliest moment to read, relative to now when positive is false. */
    val oldest: (now: Long) -> Long,
    /** Stop reading back after this many windows in a row without a record; null reads back to [oldest]. */
    val stopAfterEmpty: Int?,
    val read: (send: (BinanceRequest) -> String, from: Long, to: Long) -> List<ExchangeRow>,
)

/**
 * Refreshes every tracked Binance account through [get], which signs and sends a request with the account's key.
 *
 * Each refresh checks the key (it may only read, and must belong to the tracked account), reads the balances of every
 * wallet, then each record stream forward from where it stopped (one day of overlap) and further back until Binance's
 * launch, its retention, or a stretch without records. Trades are read per symbol, as in rotki: from the first trade
 * ID, then from the last one seen. The symbols are those Binance lists that pair two coins the account has touched,
 * or a coin with a common quote coin, plus symbols the user adds. A symbol is read again when one of its coins'
 * balance changed, or after a day. At most [MAX_REQUESTS] requests are made per account and refresh; the rest follows
 * next time. Then the account's activities are derived again from all saved records ([BinanceDecoder]).
 */
class BinanceConnector(
    private val get: (account: AccountId, request: BinanceRequest) -> String,
) : ExchangeConnector(ExchangeKind.BINANCE) {
    override val manifest = BINANCE_MANIFEST

    override fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): Boolean {
        val budget = RequestBudget<BinanceRequest>(MAX_REQUESTS) { get(account, it) }
        require(BinanceParsers.parseReadOnly(budget.send(Binance.restrictions()))) {
            "This API key can trade, transfer or withdraw; Valroot accepts only keys that can read"
        }
        val (keyUid, spot) = BinanceParsers.parseAccount(budget.send(Binance.account()))
        require(keyUid == uid) { "This API key belongs to another Binance account" }
        val now = app.clockMillis()
        val balances = balances(budget, spot)

        fun save(rows: List<ExchangeRow>) = app.exchanges.saveRows(ExchangeKind.BINANCE, account, uid, rows, BinanceDecoder.VERSION)
        var unfinished = false
        STREAMS.forEach { stream ->
            options.onProgress("Binance · ${stream.name}")
            val windows =
                StreamWindows(stream.name, stream.windowMillis, maxOf(stream.oldest(now), Binance.LAUNCH_MILLIS), stream.stopAfterEmpty)
            val complete =
                readStream(app, account, windows, now, { !budget.spent }) { from, to ->
                    if (budget.spent) null else stream.read(budget::send, from, to).also(::save).size
                }
            if (!complete) unfinished = true
        }
        if (!budget.spent) {
            save(
                BinanceParsers
                    .parseDust(
                        budget.send(Binance.dust()),
                    ).map { ExchangeRow(BinanceDecoder.DUST, it.id, it.atMillis, it.raw, "") },
            )
        }

        options.onProgress("Binance · trades")
        val pairs = if (!budget.spent) readTrades(app, account, balances, now, budget, ::save) else app.exchanges.pairs(account)
        if (pairs == null) unfinished = true

        // Symbols not matched to a coin yet: Binance's token contracts let CoinGecko's contract map name the coin.
        val known = pairs ?: app.exchanges.pairs(account)
        val unmatched =
            app.exchanges.unmatchedSymbols(
                ExchangeKind.BINANCE.id,
                app.exchanges.rowSymbols(ExchangeKind.BINANCE, account) + balances.keys,
            )
        val contracts =
            if (unmatched.isNotEmpty() && !budget.spent) {
                BinanceParsers.parseCoinContracts(budget.send(Binance.coins())).filterKeys { it in unmatched }
            } else {
                emptyMap()
            }
        val snapshot = ExchangeSnapshot(now, balances.map { (coin, amount) -> ExchangeBalance("", coin, amount) }, known, contracts)
        app.exchanges.applyHistory(ExchangeKind.BINANCE, account, uid, snapshot)
        TradeSymbols.saveBalances(app, account, balances)
        return unfinished
    }

    /**
     * Each coin's total over every wallet. Binance's wallet list gives coins per wallet; Spot, and Earn when it has a
     * value but no coin detail, come from their own requests instead.
     */
    private fun balances(
        budget: RequestBudget<BinanceRequest>,
        spot: List<BinanceBalance>,
    ): Map<String, ExactDecimal> {
        val payload = budget.send(Binance.walletBalance())
        val detailed = BinanceParsers.parseWalletBalances(payload)
        val withValue = BinanceParsers.parseWalletsWithBalance(payload)
        val all = detailed.toMutableList()
        if (detailed.none { it.wallet == "Spot" }) all += spot
        if (detailed.none { it.wallet.startsWith("Earn") } && withValue.any { it.startsWith("Earn") }) {
            for (locked in listOf(false, true)) {
                var page = 1
                while (true) {
                    val (rows, total) =
                        BinanceParsers.parseEarnPositions(
                            budget.send(if (locked) Binance.lockedPositions(page) else Binance.flexiblePositions(page)),
                            locked,
                        )
                    all += rows
                    if (rows.isEmpty() || page * Binance.EARN_PAGE >= total) break
                    page++
                }
            }
        }
        return all
            .filter { it.amount.coefficient.sign != 0 }
            .groupBy { it.coin }
            .mapValues { (_, list) -> list.map { it.amount }.reduce { a, b -> a + b } }
    }

    /**
     * Reads trades of every candidate symbol that is due; returns the symbols with their base and quote coins, or null
     * when the budget ran out first.
     */
    private fun readTrades(
        app: PortfolioApplication,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
        now: Long,
        budget: RequestBudget<BinanceRequest>,
        save: (List<ExchangeRow>) -> Unit,
    ): Map<String, Pair<String, String>>? {
        val listed = BinanceParsers.parseSymbols(budget.send(Binance.tickers()))
        val candidates = TradeSymbols.candidates(app, ExchangeKind.BINANCE, account, balances, listed, QUOTES)
        val changed = TradeSymbols.changedCoins(app, account, balances)
        val cursors = app.exchanges.cursors(account)
        for ((symbol, pair) in candidates.entries.sortedBy { it.key }) {
            val cursor = cursors[TRADE_CURSOR + symbol]?.split('|')
            val nextId = cursor?.getOrNull(0)?.toLongOrNull() ?: 0L
            val checked = cursor?.getOrNull(1)?.toLongOrNull()
            val due = checked == null || now - checked >= Binance.DAY_MILLIS || pair.first in changed || pair.second in changed
            if (!due) continue
            var fromId = nextId
            while (true) {
                if (budget.spent) return null
                val trades = BinanceParsers.parseTrades(budget.send(Binance.trades(symbol, fromId)))
                save(trades.map { ExchangeRow(BinanceDecoder.TRADES, "$symbol:${it.id}", it.atMillis, it.raw, "") })
                trades.maxOfOrNull { it.id }?.let { fromId = it + 1 }
                app.exchanges.saveCursor(
                    account,
                    TRADE_CURSOR + symbol,
                    "$fromId|${if (trades.size < Binance.TRADE_PAGE) now else checked ?: 0}",
                )
                if (trades.size < Binance.TRADE_PAGE) break
            }
        }
        return app.exchanges.pairs(account)
    }

    companion object {
        /** Requests per account and refresh; a first refresh takes a few. */
        const val MAX_REQUESTS = 600

        /** Cursor key prefix of a symbol's next trade ID and last full read. */
        const val TRADE_CURSOR = "trades:"

        /** Coins Binance quotes markets in. */
        val QUOTES = listOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "BTC", "ETH", "BNB", "EUR", "TRY", "BRL", "JPY", "DAI", "XRP")

        private val STREAMS =
            listOf(
                BinanceStream(BinanceDecoder.DEPOSITS, Binance.RECORD_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, null) { send, from, to ->
                    paged(1000) { offset ->
                        BinanceParsers
                            .parseDeposits(
                                send(Binance.deposits(from, to, offset)),
                            ).map { ExchangeRow(BinanceDecoder.DEPOSITS, it.id, it.atMillis, it.raw, "") }
                    }
                },
                BinanceStream(BinanceDecoder.WITHDRAWALS, Binance.RECORD_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, null) { send, from, to ->
                    paged(1000) { offset ->
                        BinanceParsers
                            .parseWithdrawals(send(Binance.withdrawals(from, to, offset)))
                            .map { ExchangeRow(BinanceDecoder.WITHDRAWALS, it.id, it.atMillis, it.raw, "") }
                    }
                },
                BinanceStream(BinanceDecoder.DIVIDENDS, Binance.DIVIDEND_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, null) { send, from, to ->
                    BinanceParsers.parseDividends(send(Binance.dividends(from, to))).first.map {
                        ExchangeRow(BinanceDecoder.DIVIDENDS, it.id, it.atMillis, it.raw, "")
                    }
                },
                BinanceStream(BinanceDecoder.CONVERTS, Binance.MONTH_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, 12) { send, from, to ->
                    val rows = mutableListOf<ExchangeRow>()
                    var start = from
                    while (true) {
                        val (converts, more) = BinanceParsers.parseConverts(send(Binance.converts(start, to)))
                        rows += converts.map { ExchangeRow(BinanceDecoder.CONVERTS, it.id, it.atMillis, it.raw, "") }
                        val last = converts.maxOfOrNull { it.atMillis } ?: break
                        if (!more || last + 1 >= to) break
                        start = last + 1
                    }
                    rows
                },
                rewards(BinanceDecoder.FLEXIBLE_REWARDS, locked = false),
                rewards(BinanceDecoder.LOCKED_REWARDS, locked = true),
                BinanceStream(
                    BinanceDecoder.PAY,
                    Binance.RECORD_WINDOW_MILLIS,
                    { it - Binance.PAY_RETENTION_MILLIS },
                    null,
                ) { send, from, to ->
                    BinanceParsers
                        .parsePay(
                            send(Binance.pay(from, to)),
                        ).map { ExchangeRow(BinanceDecoder.PAY, it.id, it.atMillis, it.raw, "") }
                },
                card(BinanceDecoder.CARD_BUYS, buy = true),
                card(BinanceDecoder.CARD_SALES, buy = false),
                p2p(BinanceDecoder.P2P_BUYS, buy = true),
                p2p(BinanceDecoder.P2P_SALES, buy = false),
            )

        private fun rewards(
            name: String,
            locked: Boolean,
        ) = BinanceStream(name, Binance.MONTH_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, 12) { send, from, to ->
            val rows = mutableListOf<ExchangeRow>()
            var page = 1
            while (true) {
                val request = if (locked) Binance.lockedRewards(from, to, page) else Binance.flexibleRewards(from, to, page)
                val (rewards, total) = BinanceParsers.parseRewards(send(request), locked)
                rows += rewards.map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
                if (rewards.isEmpty() || page * Binance.EARN_PAGE >= total) break
                page++
            }
            rows
        }

        private fun card(
            name: String,
            buy: Boolean,
        ) = BinanceStream(name, Binance.RECORD_WINDOW_MILLIS, { Binance.LAUNCH_MILLIS }, 12) { send, from, to ->
            paged(500, start = 1, step = 1) { page ->
                BinanceParsers
                    .parseFiatPayments(
                        send(Binance.fiatPayments(buy, from, to, page)),
                        buy,
                    ).map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
            }
        }

        private fun p2p(
            name: String,
            buy: Boolean,
        ) = BinanceStream(name, Binance.MONTH_WINDOW_MILLIS, { it - Binance.P2P_RETENTION_MILLIS }, null) { send, from, to ->
            paged(100, start = 1, step = 1) { page ->
                BinanceParsers.parseP2p(send(Binance.p2p(buy, from, to, page))).map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
            }
        }

        /** Reads pages until one is not full: by offset (step = page size) or by page number. */
        private fun paged(
            size: Int,
            start: Int = 0,
            step: Int = size,
            page: (Int) -> List<ExchangeRow>,
        ): List<ExchangeRow> {
            val rows = mutableListOf<ExchangeRow>()
            var position = start
            while (true) {
                val items = page(position)
                rows += items
                if (items.size < size) break
                position += step
            }
            return rows
        }
    }
}

/** Binance: every wallet's balances and the records Binance keeps; keys report their restrictions. */
object BinanceProvider : ExchangeProvider<BinanceRequest> {
    override val kind = ExchangeKind.BINANCE
    override val manifest = BINANCE_MANIFEST
    override val history: ExchangeHistory = BinanceDecoder

    override fun connector(transport: ExchangeTransport<BinanceRequest>) = BinanceConnector(transport::get)

    override fun checkKey(send: (BinanceRequest) -> String): ExchangeKeyInfo {
        val readOnly = BinanceParsers.parseReadOnly(send(Binance.restrictions()))
        val (uid, _) = BinanceParsers.parseAccount(send(Binance.account()))
        return ExchangeKeyInfo(uid, readOnly, null)
    }
}
