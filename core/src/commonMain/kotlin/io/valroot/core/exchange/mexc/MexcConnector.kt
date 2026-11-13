package io.valroot.core.exchange.mexc

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

/** MEXC spot accounts through an API key with read permissions. */
val MEXC_MANIFEST =
    IntegrationManifest(
        "mexc-api",
        setOf(ExchangeKind.MEXC.network),
        setOf("uid"),
        setOf(Capability.BALANCE, Capability.HISTORY, Capability.DECODING),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/** One weekly history stream and how far back MEXC keeps it. */
private data class MexcStream(
    val name: String,
    val retentionMillis: Long,
    val read: (send: (MexcRequest) -> String, from: Long, to: Long) -> List<ExchangeRow>,
)

/**
 * Refreshes every tracked MEXC account through [get], which signs and sends a request with the account's key.
 *
 * MEXC does not report a key's permissions; the key is trusted to be read-only, and only GET requests are ever sent.
 * Each refresh reads the account's user ID (it must be the tracked one) and spot balances, then each record stream in
 * 7-day windows forward from where it stopped and back as far as MEXC keeps it, dust conversions, and trades per
 * symbol. MEXC keeps a month of trades, so each symbol is read again when one of its coins' balance changed or after a
 * day, well before MEXC drops them; a window with a full page is split in half.
 * Every record is kept, so history grows from the first refresh on.
 */
class MexcConnector(
    private val get: (account: AccountId, request: MexcRequest) -> String,
) : ExchangeConnector(ExchangeKind.MEXC) {
    override val manifest = MEXC_MANIFEST

    override fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): Boolean {
        val budget = RequestBudget<MexcRequest>(MAX_REQUESTS) { get(account, it) }
        require(Mexc.parseUid(budget.send(Mexc.uid())) == uid) { "This API key belongs to another MEXC account" }
        val now = app.clockMillis()
        val balances = Mexc.parseBalances(budget.send(Mexc.account()))

        fun save(rows: List<ExchangeRow>) = app.exchanges.saveRows(ExchangeKind.MEXC, account, uid, rows, MexcDecoder.VERSION)
        var unfinished = false
        STREAMS.forEach { stream ->
            options.onProgress("MEXC · ${stream.name}")
            val windows = StreamWindows(stream.name, Mexc.WEEK_MILLIS, now - stream.retentionMillis)
            val complete =
                readStream(app, account, windows, now, { !budget.spent }) { from, to ->
                    if (budget.spent) null else stream.read(budget::send, from, to).also(::save).size
                }
            if (!complete) unfinished = true
        }
        if (!budget.spent) {
            save(Mexc.parseDust(budget.send(Mexc.dust())).first.map { ExchangeRow(MexcDecoder.DUST, it.id, it.atMillis, it.raw, "") })
        }
        options.onProgress("MEXC · trades")
        if (!readTrades(app, account, balances, now, budget, ::save)) unfinished = true

        val pairs = app.exchanges.pairs(account)
        val unmatched =
            app.exchanges.unmatchedSymbols(
                ExchangeKind.MEXC.id,
                app.exchanges.rowSymbols(ExchangeKind.MEXC, account) + balances.keys,
            )
        val contracts =
            if (unmatched.isNotEmpty() && !budget.spent) {
                Mexc.parseCoinContracts(budget.send(Mexc.coins())).filterKeys { it in unmatched }
            } else {
                emptyMap()
            }
        val snapshot = ExchangeSnapshot(now, balances.map { (coin, amount) -> ExchangeBalance("", coin, amount) }, pairs, contracts)
        app.exchanges.applyHistory(ExchangeKind.MEXC, account, uid, snapshot)
        TradeSymbols.saveBalances(app, account, balances)
        return unfinished
    }

    /** Reads trades of every candidate symbol that is due; false when the budget ran out first. */
    private fun readTrades(
        app: PortfolioApplication,
        account: AccountId,
        balances: Map<String, ExactDecimal>,
        now: Long,
        budget: RequestBudget<MexcRequest>,
        save: (List<ExchangeRow>) -> Unit,
    ): Boolean {
        if (budget.spent) return false
        val listed = Mexc.parseSymbols(budget.send(Mexc.tickers()))
        val candidates = TradeSymbols.candidates(app, ExchangeKind.MEXC, account, balances, listed, QUOTES)
        val changed = TradeSymbols.changedCoins(app, account, balances)
        val cursors = app.exchanges.cursors(account)
        for ((symbol, pair) in candidates.entries.sortedBy { it.key }) {
            val read = cursors[TRADE_CURSOR + symbol]?.toLongOrNull()
            val due = read == null || now - read >= Mexc.DAY_MILLIS || pair.first in changed || pair.second in changed
            if (!due) continue
            if (budget.spent) return false
            val from = maxOf(read?.minus(Mexc.DAY_MILLIS) ?: 0L, now - Mexc.TRADE_RETENTION_MILLIS + Mexc.DAY_MILLIS / 24)
            if (!readTradeWindow(symbol, from, now, budget, save)) return false
            app.exchanges.saveCursor(account, TRADE_CURSOR + symbol, "$now")
        }
        return true
    }

    /** Reads one symbol's trades in a window, halving it while a page comes back full; false when the budget ran out. */
    private fun readTradeWindow(
        symbol: String,
        from: Long,
        to: Long,
        budget: RequestBudget<MexcRequest>,
        save: (List<ExchangeRow>) -> Unit,
    ): Boolean {
        if (budget.spent) return false
        val trades = Mexc.parseTrades(budget.send(Mexc.trades(symbol, from, to)))
        if (trades.size >= Mexc.TRADE_PAGE && to - from > MIN_TRADE_WINDOW_MILLIS) {
            val middle = from + (to - from) / 2
            return readTradeWindow(symbol, from, middle, budget, save) && readTradeWindow(symbol, middle, to, budget, save)
        }
        save(trades.map { ExchangeRow(MexcDecoder.TRADES, "$symbol:${it.id}", it.atMillis, it.raw, "") })
        return true
    }

    companion object {
        const val MAX_REQUESTS = 300

        /** A window is not split below a minute; MEXC returns at most 100 trades in it. */
        const val MIN_TRADE_WINDOW_MILLIS = 60_000L

        const val TRADE_CURSOR = "mexc-trades:"

        /** Coins MEXC quotes markets in. */
        val QUOTES = listOf("USDT", "USDC", "USDE", "BTC", "ETH", "MX", "EUR")

        private val STREAMS =
            listOf(
                MexcStream(MexcDecoder.DEPOSITS, Mexc.RECORD_RETENTION_MILLIS) { send, from, to ->
                    Mexc
                        .parseDeposits(
                            send(Mexc.deposits(from, to)),
                        ).map { ExchangeRow(MexcDecoder.DEPOSITS, it.id, it.atMillis, it.raw, "") }
                },
                MexcStream(MexcDecoder.WITHDRAWALS, Mexc.RECORD_RETENTION_MILLIS) { send, from, to ->
                    Mexc
                        .parseWithdrawals(
                            send(Mexc.withdrawals(from, to)),
                        ).map { ExchangeRow(MexcDecoder.WITHDRAWALS, it.id, it.atMillis, it.raw, "") }
                },
                MexcStream(MexcDecoder.INTERNAL, Mexc.RECORD_RETENTION_MILLIS) { send, from, to ->
                    val rows = mutableListOf<ExchangeRow>()
                    var page = 1
                    while (true) {
                        val (transfers, pages) = Mexc.parseInternalTransfers(send(Mexc.internalTransfers(from, to, page)))
                        rows += transfers.map { ExchangeRow(MexcDecoder.INTERNAL, it.id, it.atMillis, it.raw, "") }
                        if (transfers.isEmpty() || page >= pages) break
                        page++
                    }
                    rows
                },
                futures(MexcDecoder.TO_FUTURES, fromSpot = true),
                futures(MexcDecoder.FROM_FUTURES, fromSpot = false),
            )

        private fun futures(
            name: String,
            fromSpot: Boolean,
        ) = MexcStream(name, Mexc.TRANSFER_RETENTION_MILLIS) { send, from, to ->
            val rows = mutableListOf<ExchangeRow>()
            var page = 1
            while (true) {
                val (transfers, total) = Mexc.parseUniversalTransfers(send(Mexc.universalTransfers(fromSpot, from, to, page)))
                rows += transfers.map { ExchangeRow(name, it.id, it.atMillis, it.raw, "") }
                if (transfers.isEmpty() || page * Mexc.TRANSFER_PAGE >= total) break
                page++
            }
            rows
        }
    }
}

/** MEXC: spot balances and MEXC's short history. */
object MexcProvider : ExchangeProvider<MexcRequest> {
    override val kind = ExchangeKind.MEXC
    override val manifest = MEXC_MANIFEST
    override val history: ExchangeHistory = MexcDecoder

    override fun connector(transport: ExchangeTransport<MexcRequest>) = MexcConnector(transport::get)

    /** MEXC does not report a key's permissions: the key is trusted to be read-only, and only reads are ever sent. */
    override fun checkKey(send: (MexcRequest) -> String): ExchangeKeyInfo = ExchangeKeyInfo(Mexc.parseUid(send(Mexc.uid())), true, null)
}
