package io.valroot.core.exchange

import io.valroot.core.application.SourceConnector
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.SourceRecordId

/**
 * One exchange Valroot reads: its identity and manifest, the connector that reads an account through a transport
 * sending requests of type [R], the key check used when connecting, and how its saved records become activities.
 *
 * Adding an exchange means one provider (with its client, connector and [ExchangeHistory]), one [ExchangeKind] entry,
 * a host transport and the help text on the connect screen.
 */
interface ExchangeProvider<R> {
    val kind: ExchangeKind
    val manifest: IntegrationManifest
    val history: ExchangeHistory

    fun connector(transport: ExchangeTransport<R>): SourceConnector

    /** Whose account the key behind [send] reads, and whether it can only read. */
    fun checkKey(send: (R) -> String): ExchangeKeyInfo
}

/** The exchanges Valroot supports. */
object ExchangeProviders {
    val all: List<ExchangeProvider<*>> = listOf(BybitProvider, BinanceProvider, MexcProvider)

    fun of(kind: ExchangeKind): ExchangeProvider<*> = all.single { it.kind == kind }
}

/** The account used for requests made while checking a key, before any account is tracked. */
val KEY_CHECK_ACCOUNT = AccountId("key-check")

/** One coin's balance in one of an account's wallets, as the exchange reports it. */
data class ExchangeBalance(
    /** The exchange's wallet or account type; empty when it reports one total per coin. */
    val wallet: String,
    val coin: String,
    val amount: ExactDecimal,
)

/** What a refresh read besides the saved records: balances and what the decoder needs to name coins. */
data class ExchangeSnapshot(
    val atMillis: Long,
    val balances: List<ExchangeBalance>,
    /** Trading symbols with their base and quote coin. */
    val pairs: Map<String, Pair<String, String>> = emptyMap(),
    /** The exchange's token contracts for symbols not matched to a coin yet. */
    val contracts: Map<String, List<ExchangeContract>> = emptyMap(),
    /** Earliest moment each wallet's balance log covers, for exchanges that keep one. */
    val coveredFrom: Map<String, Long> = emptyMap(),
    val keyExpiresAtMillis: Long? = null,
) {
    /** Balances that are not zero. */
    val held: List<ExchangeBalance> get() = balances.filter { it.amount.coefficient.sign != 0 }

    /** Each held coin's total over every wallet. */
    val totals: Map<String, ExactDecimal>
        get() = held.groupBy { it.coin }.mapValues { (_, list) -> list.map { it.amount }.reduce { a, b -> a + b } }
}

/** How one exchange's saved records and balances become activities. */
interface ExchangeHistory {
    /** Decoder version; records saved by another version are decoded again. */
    val version: String

    /** Coins the saved [rows] move; trades name theirs through [pairs]. */
    fun coins(
        rows: List<ExchangeRow>,
        pairs: Map<String, Pair<String, String>>,
    ): Set<String>

    /** The balances observation saved with each decode; unchanged balances give the same text. */
    fun balanceRecord(snapshot: ExchangeSnapshot): String

    /** Where the account's records begin, from the streams read so far; null when unknown. */
    fun recordsFrom(
        streams: Map<String, ExchangeStreamState>,
        snapshot: ExchangeSnapshot,
    ): Long?

    fun decode(
        account: AccountId,
        uid: String,
        rows: List<ExchangeRow>,
        assets: Map<String, AssetId>,
        snapshot: ExchangeSnapshot,
        source: SourceRecordId,
        recordsFrom: Long?,
        decimals: (AssetId) -> Int,
    ): ExchangeDecoded
}

/** Balance observation of exchanges that report one total per coin: `<id>-balances-1`, then `coin|amount` lines. */
internal fun totalsRecord(
    kind: ExchangeKind,
    snapshot: ExchangeSnapshot,
): String =
    "${kind.id}-balances-1\n" +
        snapshot.totals.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}|${it.value}" }

/** The earliest start of the deposit and withdrawal streams. */
internal fun transfersFrom(
    streams: Map<String, ExchangeStreamState>,
    deposits: String,
    withdrawals: String,
): Long? = streams.values.filter { it.stream == deposits || it.stream == withdrawals }.minOfOrNull { it.fromMillis }
