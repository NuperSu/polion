package io.valroot.core.exchange

import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A GET request to MEXC: a path and its query, sent as written; signed requests get timestamp and signature appended. */
data class MexcRequest(
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val signed: Boolean = true,
) {
    init {
        require(path.startsWith("/api/v3/") && path.all { it.isLetterOrDigit() || it in "/-_" })
        require(
            query.all { (name, value) ->
                name.all(Char::isLetterOrDigit) &&
                    value.isNotEmpty() &&
                    value.all { it.code in 33..126 && it !in "&#?=" }
            },
        )
    }

    val queryString: String get() = query.joinToString("&") { (name, value) -> "$name=$value" }
}

data class MexcTrade(
    val symbol: String,
    val id: String,
    val qty: ExactDecimal,
    val quoteQty: ExactDecimal,
    val commission: ExactDecimal,
    val commissionAsset: String,
    val isBuyer: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

data class MexcDeposit(
    val id: String,
    val coin: String,
    val network: String,
    val amount: ExactDecimal,
    val txId: String,
    val status: Int,
    val atMillis: Long,
    val raw: String = "",
) {
    /** SUCCESS (5) or COMPLETED (12). */
    val credited: Boolean get() = status == 5 || status == 12
}

data class MexcWithdrawal(
    val id: String,
    val coin: String,
    val network: String,
    val amount: ExactDecimal,
    val fee: ExactDecimal,
    val txId: String,
    val status: Int,
    val internal: Boolean,
    val atMillis: Long,
    val raw: String = "",
) {
    /** SUCCESS (7). */
    val done: Boolean get() = status == 7
}

/** A transfer to or from another MEXC user, by email, UID or phone. */
data class MexcInternalTransfer(
    val id: String,
    val asset: String,
    val amount: ExactDecimal,
    val fromAccount: String,
    val toAccount: String,
    val success: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

/** A transfer between the spot and futures accounts. */
data class MexcUniversalTransfer(
    val id: String,
    val asset: String,
    val amount: ExactDecimal,
    val fromSpot: Boolean,
    val success: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

/** One dust conversion: small balances of several coins turned into MX. */
data class MexcDust(
    val id: String,
    val atMillis: Long,
    val parts: List<Part>,
    val raw: String = "",
) {
    data class Part(
        val asset: String,
        val amount: ExactDecimal,
        /** MX received, after the fee. */
        val received: ExactDecimal,
        val fee: ExactDecimal,
    )
}

class MexcException(
    val code: Int,
    message: String,
) : Exception(message) {
    val rateLimited: Boolean get() = code == 429 || code == 510

    /** Invalid key (10072), bad signature (700002), IP not allowed (700006), no permission (700007). */
    val invalidKey: Boolean get() = code == 10072 || code == 700002 || code == 700006 || code == 700007
    val clockSkew: Boolean get() = code == 700003
}

/**
 * MEXC's Spot V3 REST API, read-only. MEXC keeps little history: trades of the last month (100 per request, per
 * symbol), deposits and withdrawals of the last 90 days (7 days per request), spot↔futures transfers of six months.
 * Valroot keeps every record it reads, so history grows from the first refresh on.
 */
object Mexc {
    const val BASE_URL = "https://api.mexc.com"
    const val RECV_WINDOW = 10_000L
    const val DAY_MILLIS = 86_400_000L
    const val WEEK_MILLIS = 7 * DAY_MILLIS

    /** Trades are kept a month; deposits, withdrawals and internal transfers 90 days; spot↔futures transfers six months. */
    const val TRADE_RETENTION_MILLIS = 30 * DAY_MILLIS
    const val RECORD_RETENTION_MILLIS = 90 * DAY_MILLIS
    const val TRANSFER_RETENTION_MILLIS = 180 * DAY_MILLIS

    const val TRADE_PAGE = 100
    const val RECORD_PAGE = 1000
    const val TRANSFER_PAGE = 100

    private val json = Json { ignoreUnknownKeys = true }

    fun uid() = MexcRequest("/api/v3/uid")

    fun account() = MexcRequest("/api/v3/account")

    fun tickers() = MexcRequest("/api/v3/ticker/price", signed = false)

    fun time() = MexcRequest("/api/v3/time", signed = false)

    fun coins() = MexcRequest("/api/v3/capital/config/getall")

    fun trades(
        symbol: String,
        fromMillis: Long,
        toMillis: Long,
    ) = MexcRequest(
        "/api/v3/myTrades",
        listOf("symbol" to symbol, "startTime" to "$fromMillis", "endTime" to "$toMillis", "limit" to "$TRADE_PAGE"),
    ).also { require(toMillis > fromMillis) }

    fun deposits(
        fromMillis: Long,
        toMillis: Long,
    ) = weekly("/api/v3/capital/deposit/hisrec", fromMillis, toMillis, "limit" to "$RECORD_PAGE")

    fun withdrawals(
        fromMillis: Long,
        toMillis: Long,
    ) = weekly("/api/v3/capital/withdraw/history", fromMillis, toMillis, "limit" to "$RECORD_PAGE")

    fun internalTransfers(
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = weekly("/api/v3/capital/transfer/internal", fromMillis, toMillis, "page" to "$page", "limit" to "$TRANSFER_PAGE")

    /** Transfers from spot to futures ([fromSpot]) or back. */
    fun universalTransfers(
        fromSpot: Boolean,
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = weekly(
        "/api/v3/capital/transfer",
        fromMillis,
        toMillis,
        "fromAccountType" to if (fromSpot) "SPOT" else "FUTURES",
        "toAccountType" to if (fromSpot) "FUTURES" else "SPOT",
        "page" to "$page",
        "size" to "$TRANSFER_PAGE",
    )

    /** Dust conversions; MEXC refuses its own paging parameters here, and returns them all without. */
    fun dust() = MexcRequest("/api/v3/capital/convert")

    private fun weekly(
        path: String,
        fromMillis: Long,
        toMillis: Long,
        vararg extra: Pair<String, String>,
    ): MexcRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= WEEK_MILLIS)
        return MexcRequest(path, listOf("startTime" to "$fromMillis", "endTime" to "$toMillis") + extra)
    }

    /** The full URL of [request]; signed requests carry timestamp, receive window and signature, the key goes in `X-MEXC-APIKEY`. */
    fun url(
        request: MexcRequest,
        secret: String?,
        timestampMillis: Long,
    ): String {
        val query =
            if (request.signed) {
                val signed =
                    (request.query + listOf("timestamp" to "$timestampMillis", "recvWindow" to "$RECV_WINDOW")).joinToString("&") {
                        "${it.first}=${it.second}"
                    }
                "$signed&signature=${hmacSha256(requireNotNull(secret).encodeToByteArray(), signed.encodeToByteArray()).toHex()}"
            } else {
                request.queryString
            }
        return BASE_URL + request.path + if (query.isEmpty()) "" else "?$query"
    }

    /** A response as JSON; an object with a `code` other than 0 or 200 throws [MexcException]. */
    fun body(payload: String): JsonElement {
        val root = json.parseToJsonElement(payload)
        if (root is JsonObject) {
            val code = (root["code"] as? JsonPrimitive)?.content
            if (code != null && code != "0" && code != "200") {
                throw MexcException(code.toIntOrNull() ?: -1, root.text("msg").ifBlank { "MEXC error $code" })
            }
        }
        return root
    }

    fun parseServerTime(payload: String): Long =
        requireNotNull((body(payload) as JsonObject).text("serverTime").toLongOrNull()) {
            "Unexpected time from MEXC"
        }

    fun parseUid(payload: String): String =
        (body(payload) as JsonObject).text("uid").also { uid ->
            require(
                uid.isNotBlank() && uid.length <= 64 && uid.all { it.isLetterOrDigit() },
            ) { "Unexpected account from MEXC" }
        }

    /** Spot balances: available and frozen together. */
    fun parseBalances(payload: String): Map<String, ExactDecimal> =
        (body(payload) as JsonObject)
            .array("balances")
            .mapNotNull { element ->
                val b = element.jsonObject
                val free = decimal(b.text("free")) ?: return@mapNotNull null
                b.text("asset") to (free + (decimal(b.text("locked")) ?: ZERO))
            }.filter { it.second.coefficient.sign != 0 }
            .toMap()

    fun parseSymbols(payload: String): Set<String> =
        (body(payload) as JsonArray).mapNotNull { (it as? JsonObject)?.text("symbol")?.takeIf(String::isNotBlank) }.toSet()

    /** Each coin's token contracts on the networks MEXC supports. */
    fun parseCoinContracts(payload: String): Map<String, List<ExchangeContract>> =
        (body(payload) as JsonArray)
            .associate { element ->
                val c = element.jsonObject
                c.text("coin") to
                    c.array("networkList").mapNotNull { n ->
                        val network = n.jsonObject
                        network
                            .text("contract")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                ExchangeContract(
                                    network.text("netWork").ifBlank { network.text("network") },
                                    network.text("network"),
                                    it,
                                )
                            }
                    }
            }.filterKeys { it.isNotBlank() }

    fun parseTrades(payload: String): List<MexcTrade> = (body(payload) as JsonArray).map { trade(it.jsonObject) }

    fun parseDeposits(payload: String): List<MexcDeposit> = (body(payload) as JsonArray).map { deposit(it.jsonObject) }

    fun parseWithdrawals(payload: String): List<MexcWithdrawal> = (body(payload) as JsonArray).map { withdrawal(it.jsonObject) }

    /** One page of internal transfers and the number of pages. */
    fun parseInternalTransfers(payload: String): Pair<List<MexcInternalTransfer>, Int> {
        val o = body(payload) as JsonObject
        return o.array("data").map { internalTransfer(it.jsonObject) } to (o.text("totalPageNum").toIntOrNull() ?: 1)
    }

    fun parseUniversalTransfers(payload: String): Pair<List<MexcUniversalTransfer>, Int> {
        val o = body(payload) as JsonObject
        val rows = o.array("rows").map { universalTransfer(it.jsonObject) }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    /** One page of dust conversions and the number of pages. */
    fun parseDust(payload: String): Pair<List<MexcDust>, Int> {
        val o = body(payload) as JsonObject
        return o.array("data").map { dust(it.jsonObject) } to (o.text("totalPageNum").toIntOrNull() ?: 1)
    }

    fun row(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    fun trade(o: JsonObject) =
        MexcTrade(
            symbol = o.text("symbol"),
            id = o.text("id").also { require(it.isNotBlank()) { "Unexpected trade from MEXC" } },
            qty = required(o, "qty"),
            quoteQty = required(o, "quoteQty"),
            commission = decimal(o.text("commission")) ?: ZERO,
            commissionAsset = o.text("commissionAsset"),
            isBuyer = o.text("isBuyer") == "true",
            atMillis = millis(o, "time"),
            raw = o.toString(),
        )

    /** Deposits have no ID; the transaction, coin, network and time identify one. */
    fun deposit(o: JsonObject) =
        MexcDeposit(
            id = listOf(o.text("txId"), o.text("coin"), o.text("network"), o.text("insertTime")).joinToString("|"),
            coin = o.text("coin"),
            network = o.text("network"),
            amount = required(o, "amount"),
            txId = o.text("txId").substringBefore(':'),
            status = o.text("status").toIntOrNull() ?: 0,
            atMillis = millis(o, "insertTime"),
            raw = o.toString(),
        )

    fun withdrawal(o: JsonObject) =
        MexcWithdrawal(
            id = o.text("id"),
            coin = o.text("coin"),
            network = o.text("network"),
            amount = required(o, "amount"),
            fee = decimal(o.text("transactionFee")) ?: ZERO,
            txId = o.text("transHash").ifBlank { o.text("txId") },
            status = o.text("status").toIntOrNull() ?: 0,
            internal = o.text("transferType") == "1",
            atMillis = millis(o, "applyTime"),
            raw = o.toString(),
        )

    fun internalTransfer(o: JsonObject) =
        MexcInternalTransfer(
            id =
                o
                    .text(
                        "tranId",
                    ).ifBlank { listOf(o.text("asset"), o.text("amount"), o.text("timestamp"), o.text("toAccount")).joinToString("|") },
            asset = o.text("asset"),
            amount = required(o, "amount"),
            fromAccount = o.text("fromAccount"),
            toAccount = o.text("toAccount"),
            success = o.text("status") == "SUCCESS",
            atMillis = millis(o, "timestamp"),
            raw = o.toString(),
        )

    fun universalTransfer(o: JsonObject) =
        MexcUniversalTransfer(
            id = o.text("tranId"),
            asset = o.text("asset"),
            amount = required(o, "amount"),
            fromSpot = o.text("fromAccountType") == "SPOT",
            success = o.text("status") == "SUCCESS",
            atMillis = millis(o, "timestamp"),
            raw = o.toString(),
        )

    /** A conversion has no ID of its own; its time and parts' IDs identify it. */
    fun dust(o: JsonObject): MexcDust {
        val details = o.array("convertDetails").map { it.jsonObject }
        return MexcDust(
            id = o.text("convertTime") + ":" + details.joinToString(",") { it.text("id") }.take(200),
            atMillis = millis(o, "convertTime"),
            parts =
                details.map { d ->
                    MexcDust.Part(d.text("asset"), required(d, "amount"), required(d, "convert"), decimal(d.text("fee")) ?: ZERO)
                },
            raw = o.toString(),
        )
    }

    private val ZERO = ExactDecimal.parse("0")

    private fun JsonObject.text(name: String): String =
        when (val value = this[name]) {
            null, is JsonNull -> ""
            is JsonPrimitive -> value.content
            else -> ""
        }

    private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

    private fun decimal(text: String): ExactDecimal? =
        text
            .takeIf {
                it.isNotBlank()
            }?.let { runCatching { ExactDecimal.parse(it) }.getOrNull() }

    private fun required(
        o: JsonObject,
        name: String,
    ): ExactDecimal = requireNotNull(decimal(o.text(name))) { "Unexpected $name from MEXC" }

    private fun millis(
        o: JsonObject,
        name: String,
    ): Long = requireNotNull(o.text(name).toLongOrNull()) { "Unexpected $name from MEXC" }
}
