package io.valroot.core.exchange

import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A GET request to Binance: a path and its query, sent as written. Signed requests get `timestamp`, `recvWindow` and
 * `signature` appended by [Binance.signedQuery].
 */
data class BinanceRequest(
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val signed: Boolean = true,
) {
    init {
        require((path.startsWith("/api/v3/") || path.startsWith("/sapi/v1/")) && path.all { it.isLetterOrDigit() || it in "/-_" })
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

/** One balance: a coin in one Binance wallet (Spot, Funding, Earn, …). */
data class BinanceBalance(
    val wallet: String,
    val coin: String,
    val amount: ExactDecimal,
)

/** One fill of a spot order. */
data class BinanceTrade(
    val symbol: String,
    val id: Long,
    val orderId: String,
    val qty: ExactDecimal,
    val quoteQty: ExactDecimal,
    val commission: ExactDecimal,
    val commissionAsset: String,
    val isBuyer: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

data class BinanceDeposit(
    val id: String,
    val coin: String,
    val network: String,
    val amount: ExactDecimal,
    val txId: String,
    val status: Int,
    /** 1 for a transfer from another Binance user, 0 on chain. */
    val internal: Boolean,
    val atMillis: Long,
    val raw: String = "",
) {
    /** Success (1), or credited but not yet withdrawable (6). */
    val credited: Boolean get() = status == 1 || status == 6
}

data class BinanceWithdrawal(
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
    /** Completed (6). Rejected (3) and other states move nothing. */
    val done: Boolean get() = status == 6
}

data class BinanceConvert(
    val id: String,
    val fromAsset: String,
    val fromAmount: ExactDecimal,
    val toAsset: String,
    val toAmount: ExactDecimal,
    val success: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

/** One dust conversion: small balances of several coins turned into BNB. */
data class BinanceDust(
    val id: String,
    val atMillis: Long,
    val parts: List<Part>,
    val raw: String = "",
) {
    data class Part(
        val fromAsset: String,
        val amount: ExactDecimal,
        val received: ExactDecimal,
        val fee: ExactDecimal,
        val targetAsset: String,
    )
}

/** A distribution: airdrops, staking and savings payouts, and the like; [amount] is signed. */
data class BinanceDividend(
    val id: String,
    val asset: String,
    val amount: ExactDecimal,
    val info: String,
    val atMillis: Long,
    val raw: String = "",
)

/** A Simple Earn reward, flexible or locked. */
data class BinanceReward(
    val id: String,
    val asset: String,
    val amount: ExactDecimal,
    val atMillis: Long,
    val raw: String = "",
)

/** A Binance Pay transaction; [amount] is positive when received. */
data class BinancePay(
    val id: String,
    val currency: String,
    val amount: ExactDecimal,
    val atMillis: Long,
    val raw: String = "",
)

/** Crypto bought or sold with a card or bank transfer, outside the account's balances. */
data class BinanceFiatPayment(
    val id: String,
    val buy: Boolean,
    val crypto: String,
    val cryptoAmount: ExactDecimal,
    val completed: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

/** A P2P order: crypto bought from or sold to another user for fiat paid outside Binance. */
data class BinanceP2p(
    val id: String,
    val buy: Boolean,
    val asset: String,
    val amount: ExactDecimal,
    val commission: ExactDecimal,
    val completed: Boolean,
    val atMillis: Long,
    val raw: String = "",
)

class BinanceException(
    val code: Int,
    message: String,
) : Exception(message) {
    val rateLimited: Boolean get() = code == -1003 || code == 429 || code == 418
    val invalidKey: Boolean get() = code in setOf(-2014, -2015, -1022, 401)
    val clockSkew: Boolean get() = code == -1021
}

/**
 * Binance's spot and wallet REST API, read-only. Every path is relative to [BASE_URL]; the host signs each
 * [BinanceRequest] with [signature] and never shows the secret to this code.
 *
 * Binance keeps no balance log: history is assembled from separate records (trades per symbol, converts, dust,
 * deposits, withdrawals, distributions, Simple Earn rewards, Pay, card buys and P2P), each with its own window.
 */
@OptIn(ExperimentalTime::class)
object Binance {
    const val BASE_URL = "https://api.binance.com"
    const val RECV_WINDOW = 10_000L
    const val DAY_MILLIS = 86_400_000L

    /** Binance opened on 2017-07-14. */
    const val LAUNCH_MILLIS = 1_500_001_200_000L

    /** Deposits, withdrawals and Pay accept less than 90 days per request. */
    const val RECORD_WINDOW_MILLIS = 89 * DAY_MILLIS
    const val DIVIDEND_WINDOW_MILLIS = 180 * DAY_MILLIS

    /** Converts, Simple Earn rewards and P2P accept 30 days per request. */
    const val MONTH_WINDOW_MILLIS = 30 * DAY_MILLIS

    /** Pay keeps 18 months, P2P 6 months. */
    const val PAY_RETENTION_MILLIS = 540 * DAY_MILLIS
    const val P2P_RETENTION_MILLIS = 180 * DAY_MILLIS

    const val TRADE_PAGE = 1000
    const val RECORD_PAGE = 1000
    const val EARN_PAGE = 100

    private val json = Json { ignoreUnknownKeys = true }

    fun restrictions() = BinanceRequest("/sapi/v1/account/apiRestrictions")

    fun account() = BinanceRequest("/api/v3/account", listOf("omitZeroBalances" to "true"))

    fun walletBalance() = BinanceRequest("/sapi/v1/asset/wallet/balance", listOf("needBalanceDetail" to "true"))

    fun flexiblePositions(page: Int) =
        BinanceRequest("/sapi/v1/simple-earn/flexible/position", listOf("current" to "$page", "size" to "$EARN_PAGE"))

    fun lockedPositions(page: Int) =
        BinanceRequest(
            "/sapi/v1/simple-earn/locked/position",
            listOf(
                "current" to "$page",
                "size" to "$EARN_PAGE",
            ),
        )

    /** Every listed spot symbol with its price; public. */
    fun tickers() = BinanceRequest("/api/v3/ticker/price", signed = false)

    fun time() = BinanceRequest("/api/v3/time", signed = false)

    fun coins() = BinanceRequest("/sapi/v1/capital/config/getall")

    fun trades(
        symbol: String,
        fromId: Long,
    ) = BinanceRequest("/api/v3/myTrades", listOf("symbol" to symbol, "fromId" to "$fromId", "limit" to "$TRADE_PAGE"))

    fun deposits(
        fromMillis: Long,
        toMillis: Long,
        offset: Int = 0,
    ) = windowed(
        "/sapi/v1/capital/deposit/hisrec",
        fromMillis,
        toMillis,
        RECORD_WINDOW_MILLIS,
        "offset" to "$offset",
        "limit" to "$RECORD_PAGE",
    )

    fun withdrawals(
        fromMillis: Long,
        toMillis: Long,
        offset: Int = 0,
    ) = windowed(
        "/sapi/v1/capital/withdraw/history",
        fromMillis,
        toMillis,
        RECORD_WINDOW_MILLIS,
        "offset" to "$offset",
        "limit" to "$RECORD_PAGE",
    )

    fun converts(
        fromMillis: Long,
        toMillis: Long,
    ) = windowed("/sapi/v1/convert/tradeFlow", fromMillis, toMillis, MONTH_WINDOW_MILLIS, "limit" to "1000")

    fun dust() = BinanceRequest("/sapi/v1/asset/dribblet")

    fun dividends(
        fromMillis: Long,
        toMillis: Long,
    ) = windowed("/sapi/v1/asset/assetDividend", fromMillis, toMillis, DIVIDEND_WINDOW_MILLIS, "limit" to "500")

    fun flexibleRewards(
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = windowed(
        "/sapi/v1/simple-earn/flexible/history/rewardsRecord",
        fromMillis,
        toMillis,
        MONTH_WINDOW_MILLIS,
        "type" to "ALL",
        "current" to "$page",
        "size" to "$EARN_PAGE",
    )

    fun lockedRewards(
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = windowed(
        "/sapi/v1/simple-earn/locked/history/rewardsRecord",
        fromMillis,
        toMillis,
        MONTH_WINDOW_MILLIS,
        "current" to "$page",
        "size" to "$EARN_PAGE",
    )

    fun pay(
        fromMillis: Long,
        toMillis: Long,
    ) = windowed("/sapi/v1/pay/transactions", fromMillis, toMillis, RECORD_WINDOW_MILLIS, "limit" to "100")

    fun fiatPayments(
        buy: Boolean,
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = BinanceRequest(
        "/sapi/v1/fiat/payments",
        listOf(
            "transactionType" to if (buy) "0" else "1",
            "beginTime" to "$fromMillis",
            "endTime" to "$toMillis",
            "page" to "$page",
            "rows" to "500",
        ),
    ).also { require(toMillis > fromMillis && toMillis - fromMillis <= RECORD_WINDOW_MILLIS) }

    fun p2p(
        buy: Boolean,
        fromMillis: Long,
        toMillis: Long,
        page: Int,
    ) = BinanceRequest(
        "/sapi/v1/c2c/orderMatch/listUserOrderHistory",
        listOf(
            "tradeType" to if (buy) "BUY" else "SELL",
            "startTimestamp" to "$fromMillis",
            "endTimestamp" to "$toMillis",
            "page" to "$page",
            "rows" to "100",
        ),
    ).also { require(toMillis > fromMillis && toMillis - fromMillis <= MONTH_WINDOW_MILLIS) }

    private fun windowed(
        path: String,
        fromMillis: Long,
        toMillis: Long,
        window: Long,
        vararg extra: Pair<String, String>,
    ): BinanceRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= window)
        return BinanceRequest(path, listOf("startTime" to "$fromMillis", "endTime" to "$toMillis") + extra)
    }

    /** The query a signed request sends before its signature: the request's own, then timestamp and receive window. */
    fun signedQuery(
        request: BinanceRequest,
        timestampMillis: Long,
    ): String =
        (request.query + listOf("timestamp" to "$timestampMillis", "recvWindow" to "$RECV_WINDOW")).joinToString("&") {
            "${it.first}=${it.second}"
        }

    /** Hex HMAC-SHA256 of [query] under the secret, sent as the `signature` parameter. */
    fun signature(
        secret: String,
        query: String,
    ): String = hmacSha256(secret.encodeToByteArray(), query.encodeToByteArray()).toHex()

    /** The full URL of [request]; signed requests carry their signature, and the key goes in `X-MBX-APIKEY`. */
    fun url(
        request: BinanceRequest,
        secret: String?,
        timestampMillis: Long,
    ): String {
        val query =
            if (request.signed) {
                val signed = signedQuery(request, timestampMillis)
                "$signed&signature=${signature(requireNotNull(secret), signed)}"
            } else {
                request.queryString
            }
        return BASE_URL + request.path + if (query.isEmpty()) "" else "?$query"
    }

    /** A response as JSON; an error object (`code` below zero, or a non-success string code) throws [BinanceException]. */
    fun body(payload: String): JsonElement {
        val root = json.parseToJsonElement(payload)
        if (root is JsonObject) {
            // Spot errors have a negative number; Pay, card and P2P answer a string, "000000" on success.
            val primitive = root["code"] as? JsonPrimitive
            val code = primitive?.content
            val number = code?.toIntOrNull()
            val failed =
                when {
                    primitive == null -> false
                    primitive.isString -> code != "000000"
                    else -> number == null || number < 0
                }
            if (failed) {
                throw BinanceException(
                    number ?: -1,
                    root.text("msg").ifBlank { root.text("message") }.ifBlank { "Binance error $code" },
                )
            }
            if (root["success"] is JsonPrimitive && (root["success"] as JsonPrimitive).content == "false") {
                throw BinanceException(-1, root.text("message").ifBlank { "Binance refused the request" })
            }
        }
        return root
    }

    fun parseServerTime(payload: String): Long =
        requireNotNull((body(payload) as JsonObject).text("serverTime").toLongOrNull()) { "Unexpected time from Binance" }

    /** True when the key can only read: every trading, transfer and withdrawal permission is off. */
    fun parseReadOnly(payload: String): Boolean {
        val o = body(payload) as JsonObject
        val writes =
            listOf(
                "enableWithdrawals",
                "enableInternalTransfer",
                "enableSpotAndMarginTrading",
                "enableMargin",
                "enableFutures",
                "permitsUniversalTransfer",
                "enableVanillaOptions",
                "enablePortfolioMarginTrading",
                "enableFixApiTrade",
            )
        return o.text("enableReading") == "true" && writes.none { o.text(it) == "true" }
    }

    /** The account's user ID and its Spot balances. */
    fun parseAccount(payload: String): Pair<String, List<BinanceBalance>> {
        val o = body(payload) as JsonObject
        val uid = o.text("uid").also { require(it.isNotBlank() && it.all(Char::isDigit)) { "Unexpected account from Binance" } }
        val balances =
            o.array("balances").mapNotNull { element ->
                val b = element.jsonObject
                sum(b, "free", "locked")?.let { BinanceBalance("Spot", b.text("asset"), it) }
            }
        return uid to balances
    }

    /** Every wallet's coins, when Binance gives the detail; a wallet without detail is left out. */
    fun parseWalletBalances(payload: String): List<BinanceBalance> =
        (body(payload) as JsonArray).flatMap { element ->
            val wallet = element.jsonObject
            val name = wallet.text("walletName")
            wallet.array("assetBalances").mapNotNull { asset ->
                val a = asset.jsonObject
                sum(a, "free", "locked", "freeze", "withdrawing")?.let { BinanceBalance(name, a.text("asset"), it) }
            }
        }

    /** Wallet names Binance reports a non-zero balance for, valued in BTC, with or without coin detail. */
    fun parseWalletsWithBalance(payload: String): Set<String> =
        (body(payload) as JsonArray)
            .map { it.jsonObject }
            .filter { w -> decimal(w.text("balance"))?.let { it.coefficient.sign != 0 } == true }
            .map { it.text("walletName") }
            .toSet()

    /** One page of Simple Earn positions, flexible ([locked] false) or locked, and the total count. */
    fun parseEarnPositions(
        payload: String,
        locked: Boolean,
    ): Pair<List<BinanceBalance>, Int> {
        val o = body(payload) as JsonObject
        val rows =
            o.array("rows").mapNotNull { element ->
                val r = element.jsonObject
                decimal(r.text(if (locked) "amount" else "totalAmount"))?.let {
                    BinanceBalance(if (locked) "Earn locked" else "Earn flexible", r.text("asset"), it)
                }
            }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    /** Every listed spot symbol. */
    fun parseSymbols(payload: String): Set<String> =
        (body(payload) as JsonArray).mapNotNull { (it as? JsonObject)?.text("symbol")?.takeIf(String::isNotBlank) }.toSet()

    /** Each coin's token contracts on the networks Binance supports. */
    fun parseCoinContracts(payload: String): Map<String, List<ExchangeContract>> =
        (body(payload) as JsonArray)
            .associate { element ->
                val c = element.jsonObject
                c.text("coin") to
                    c.array("networkList").mapNotNull { n ->
                        val network = n.jsonObject
                        network
                            .text("contractAddress")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { ExchangeContract(network.text("network"), network.text("name"), it) }
                    }
            }.filterKeys { it.isNotBlank() }

    fun parseTrades(payload: String): List<BinanceTrade> = (body(payload) as JsonArray).map { trade(it.jsonObject) }

    fun parseDeposits(payload: String): List<BinanceDeposit> = (body(payload) as JsonArray).map { deposit(it.jsonObject) }

    fun parseWithdrawals(payload: String): List<BinanceWithdrawal> = (body(payload) as JsonArray).map { withdrawal(it.jsonObject) }

    /** Converts in the window, and whether Binance has more. */
    fun parseConverts(payload: String): Pair<List<BinanceConvert>, Boolean> {
        val o = body(payload) as JsonObject
        return o.array("list").map { convert(it.jsonObject) } to (o.text("moreData") == "true")
    }

    fun parseDust(payload: String): List<BinanceDust> =
        (body(payload) as JsonObject).array("userAssetDribblets").map { dust(it.jsonObject) }

    fun parseDividends(payload: String): Pair<List<BinanceDividend>, Int> {
        val o = body(payload) as JsonObject
        val rows = o.array("rows").map { dividend(it.jsonObject) }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    fun parseRewards(
        payload: String,
        locked: Boolean,
    ): Pair<List<BinanceReward>, Int> {
        val o = body(payload) as JsonObject
        val rows = o.array("rows").map { reward(it.jsonObject, locked) }
        return rows to (o.text("total").toIntOrNull() ?: rows.size)
    }

    fun parsePay(payload: String): List<BinancePay> = (body(payload) as JsonObject).array("data").map { pay(it.jsonObject) }

    fun parseFiatPayments(
        payload: String,
        buy: Boolean,
    ): List<BinanceFiatPayment> = (body(payload) as JsonObject).array("data").map { fiatPayment(it.jsonObject, buy) }

    fun parseP2p(payload: String): List<BinanceP2p> = (body(payload) as JsonObject).array("data").map { p2p(it.jsonObject) }

    /** A row saved as raw JSON, parsed again. */
    fun row(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    fun trade(o: JsonObject) =
        BinanceTrade(
            symbol = o.text("symbol"),
            id = requireNotNull(o.text("id").toLongOrNull()) { "Unexpected trade from Binance" },
            orderId = o.text("orderId"),
            qty = required(o, "qty"),
            quoteQty = required(o, "quoteQty"),
            commission = decimal(o.text("commission")) ?: ZERO,
            commissionAsset = o.text("commissionAsset"),
            isBuyer = o.text("isBuyer") == "true",
            atMillis = millis(o, "time"),
            raw = o.toString(),
        )

    fun deposit(o: JsonObject) =
        BinanceDeposit(
            id = o.text("id").ifBlank { o.text("txId") },
            coin = o.text("coin"),
            network = o.text("network"),
            amount = required(o, "amount"),
            txId = o.text("txId"),
            status = o.text("status").toIntOrNull() ?: 0,
            internal = o.text("transferType") == "1",
            atMillis = millis(o, "insertTime"),
            raw = o.toString(),
        )

    fun withdrawal(o: JsonObject): BinanceWithdrawal {
        val txId = o.text("txId")
        return BinanceWithdrawal(
            id = o.text("id"),
            coin = o.text("coin"),
            network = o.text("network"),
            amount = required(o, "amount"),
            fee = decimal(o.text("transactionFee")) ?: ZERO,
            txId = txId,
            status = o.text("status").toIntOrNull() ?: 0,
            internal = o.text("transferType") == "1" || txId.startsWith("Off-chain", ignoreCase = true),
            atMillis = utc(o.text("applyTime")),
            raw = o.toString(),
        )
    }

    fun convert(o: JsonObject) =
        BinanceConvert(
            id = o.text("orderId").ifBlank { o.text("quoteId") },
            fromAsset = o.text("fromAsset"),
            fromAmount = required(o, "fromAmount"),
            toAsset = o.text("toAsset"),
            toAmount = required(o, "toAmount"),
            success = o.text("orderStatus") == "SUCCESS",
            atMillis = millis(o, "createTime"),
            raw = o.toString(),
        )

    fun dust(o: JsonObject) =
        BinanceDust(
            id = o.text("transId"),
            atMillis = millis(o, "operateTime"),
            parts =
                o.array("userAssetDribbletDetails").map { element ->
                    val d = element.jsonObject
                    BinanceDust.Part(
                        d.text("fromAsset"),
                        required(d, "amount"),
                        required(d, "transferedAmount"),
                        decimal(d.text("serviceChargeAmount")) ?: ZERO,
                        d.text("targetAsset").ifBlank { "BNB" },
                    )
                },
            raw = o.toString(),
        )

    fun dividend(o: JsonObject): BinanceDividend {
        val amount = required(o, "amount")
        val outgoing = o.text("direction") == "-1" && amount.coefficient.sign > 0
        return BinanceDividend(
            id = o.text("id").ifBlank { o.text("tranId") },
            asset = o.text("asset"),
            amount = if (outgoing) ExactDecimal(-amount.coefficient, amount.scale) else amount,
            info = o.text("enInfo"),
            atMillis = millis(o, "divTime"),
            raw = o.toString(),
        )
    }

    /** Rewards have no ID; the asset, time, type, product and amount identify one. */
    fun reward(
        o: JsonObject,
        locked: Boolean,
    ): BinanceReward {
        val amount = required(o, if (locked) "amount" else "rewards")
        val asset = o.text("asset")
        val at = millis(o, "time")
        val product = if (locked) o.text("positionId") else o.text("projectId")
        return BinanceReward(
            id = listOf(if (locked) "locked" else "flexible", asset, "$at", o.text("type"), product, "$amount").joinToString("|"),
            asset = asset,
            amount = amount,
            atMillis = at,
            raw = o.toString(),
        )
    }

    fun pay(o: JsonObject) =
        BinancePay(
            id = o.text("transactionId"),
            currency = o.text("currency"),
            amount = required(o, "amount"),
            atMillis = millis(o, "transactionTime"),
            raw = o.toString(),
        )

    fun fiatPayment(
        o: JsonObject,
        buy: Boolean,
    ) = BinanceFiatPayment(
        id = o.text("orderNo"),
        buy = buy,
        crypto = o.text("cryptoCurrency"),
        cryptoAmount = required(o, "obtainAmount"),
        completed = o.text("status").equals("Completed", ignoreCase = true),
        atMillis = millis(o, "createTime"),
        raw = o.toString(),
    )

    fun p2p(o: JsonObject) =
        BinanceP2p(
            id = o.text("orderNumber"),
            buy = o.text("tradeType") == "BUY",
            asset = o.text("asset"),
            amount = required(o, "amount"),
            commission = decimal(o.text("commission")) ?: ZERO,
            completed = o.text("orderStatus") == "COMPLETED",
            atMillis = millis(o, "createTime"),
            raw = o.toString(),
        )

    private val ZERO = ExactDecimal.parse("0")

    /** Binance's withdrawal times are `yyyy-MM-dd HH:mm:ss` in UTC. */
    private fun utc(text: String): Long =
        text.toLongOrNull()
            ?: requireNotNull(runCatching { Instant.parse(text.trim().replace(' ', 'T') + "Z").toEpochMilliseconds() }.getOrNull()) {
                "Unexpected time from Binance"
            }

    private fun sum(
        o: JsonObject,
        vararg names: String,
    ): ExactDecimal? {
        val parts = names.mapNotNull { decimal(o.text(it)) }
        if (parts.isEmpty()) return null
        return parts.reduce { a, b -> a + b }
    }

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
    ): ExactDecimal = requireNotNull(decimal(o.text(name))) { "Unexpected $name from Binance" }

    private fun millis(
        o: JsonObject,
        name: String,
    ): Long = requireNotNull(o.text(name).toLongOrNull()) { "Unexpected $name from Binance" }
}
