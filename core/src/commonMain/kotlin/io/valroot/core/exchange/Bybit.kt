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
 * A signed GET request to Bybit: a path and its query. The query is sent and signed exactly as written, without
 * percent-encoding, as Bybit's own clients do; cursors arrive already encoded.
 */
data class BybitRequest(
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(path.startsWith("/v5/") && path.all { it.isLetterOrDigit() || it in "/-_" })
        require(
            query.all { (name, value) ->
                name.all(Char::isLetterOrDigit) &&
                    value.isNotEmpty() &&
                    value.all { it.code in 33..126 && it !in "&#?" }
            },
        )
    }

    val queryString: String get() = query.joinToString("&") { (name, value) -> "$name=$value" }
}

/** What a Bybit API key may do and whose account it reads. */
data class BybitKeyInfo(
    val uid: String,
    val readOnly: Boolean,
    val unified: Boolean,
    val ipBound: Boolean,
    /** When the key stops working; null when it does not expire. */
    val expiresAtMillis: Long?,
)

/** One page of a cursor-paginated list. [next] is null on the last page. */
data class BybitPage<T>(
    val items: List<T>,
    val next: String?,
)

/** A row of the Unified Trading Account transaction log: one balance change of one coin. */
data class BybitLogEntry(
    val id: String,
    val type: String,
    val category: String,
    val symbol: String,
    val currency: String,
    /** Net balance change: [cashFlow] + [funding] - [fee]. */
    val change: ExactDecimal,
    val cashFlow: ExactDecimal,
    val funding: ExactDecimal,
    /** Trading fee; negative for a rebate. */
    val fee: ExactDecimal,
    /** The coin's wallet balance after this change. */
    val balanceAfter: ExactDecimal,
    val tradeId: String,
    val orderId: String,
    val atMillis: Long,
    /** Bybit's row as received. */
    val raw: String = "",
) {
    /** The log's `id` is shared by related rows, so identity also includes the type, coin, trade and time. */
    val key: String get() = listOf(id, type, currency, tradeId, atMillis.toString()).joinToString("|")
}

/** A row of the Funding Account history: one balance change of one coin. */
data class BybitFundingEntry(
    /** Bybit's unique row ID. */
    val id: String,
    val currency: String,
    /** Signed change: positive in, negative out. */
    val change: ExactDecimal,
    val balanceAfter: ExactDecimal,
    val atMillis: Long,
    val business: String,
    val description: String,
    val raw: String = "",
)

data class BybitDeposit(
    val id: String,
    val coin: String,
    val chain: String,
    val amount: ExactDecimal,
    val txId: String,
    val status: Int,
    val atMillis: Long,
    val raw: String = "",
) {
    /** Credited and not rolled back (status 3, or 70012 after a rollback review). */
    val credited: Boolean get() = status == 3 || status == 70012
}

data class BybitWithdrawal(
    val id: String,
    val coin: String,
    val chain: String,
    val amount: ExactDecimal,
    val fee: ExactDecimal,
    val txId: String,
    val status: String,
    val atMillis: Long,
    val onChain: Boolean,
    val raw: String = "",
) {
    val done: Boolean get() = status == "success" || status == "BlockchainConfirmed"
    val failed: Boolean get() = status in setOf("CancelByUser", "Reject", "Fail") || status.startsWith("HighValueReviewRejected")
}

data class BybitConvert(
    val id: String,
    val fromCoin: String,
    val fromAmount: ExactDecimal,
    val toCoin: String,
    val toAmount: ExactDecimal,
    val status: String,
    val atMillis: Long,
    val raw: String = "",
)

/** A coin's balance in one Bybit account type (FUND, UNIFIED, Earn, TradingBot, …). */
data class BybitBalance(
    val accountType: String,
    val coin: String,
    val amount: ExactDecimal,
)

class BybitException(
    code: Int,
    message: String,
) : ExchangeApiException(code, message) {
    override val rateLimited: Boolean get() = code == RATE_LIMITED || code == HTTP_FORBIDDEN
    override val invalidKey: Boolean get() = code in INVALID_KEY
    override val clockSkew: Boolean get() = code == CLOCK_SKEW

    companion object {
        const val RATE_LIMITED = 10006
        const val CLOCK_SKEW = 10002

        /** Bybit answers HTTP 403 when an IP sends requests too often. */
        const val HTTP_FORBIDDEN = 403
        val INVALID_KEY = setOf(10003, 10004, 10005, 10007, 10010, 33004)
    }
}

/**
 * Bybit's V5 REST API, read-only. Every path is relative to [BASE_URL]; the host signs each [BybitRequest] with
 * [signature] and never shows the secret to this code.
 *
 * History comes from two balance logs that record every change with the balance after it: the Unified Trading
 * Account's transaction log (7-day windows, two years back) and the Funding Account's history (7-day windows).
 * Deposit, withdrawal and convert records add on-chain transaction IDs and pair the two sides of a convert.
 */
@OptIn(ExperimentalTime::class)
object Bybit {
    const val BASE_URL = "https://api.bybit.com"
    const val RECV_WINDOW = 10_000L
    const val DAY_MILLIS = 86_400_000L

    /** Both balance logs accept at most 7 days per request. */
    const val LOG_WINDOW_MILLIS = 7 * DAY_MILLIS

    /** Deposit and withdrawal records accept less than 30 days per request. */
    const val RECORD_WINDOW_MILLIS = 30 * DAY_MILLIS - 1_000

    /** The transaction log keeps two years and refuses windows that start earlier; a day of margin keeps clear of it. */
    const val LOG_RETENTION_MILLIS = 729 * DAY_MILLIS

    private val json = Json { ignoreUnknownKeys = true }

    fun keyInfo() = BybitRequest("/v5/user/query-api")

    fun serverTime() = BybitRequest("/v5/market/time")

    fun unifiedBalance() = BybitRequest("/v5/account/wallet-balance", listOf("accountType" to "UNIFIED"))

    fun fundingBalance() = BybitRequest("/v5/asset/transfer/query-account-coins-balance", listOf("accountType" to "FUND"))

    fun overview() = BybitRequest("/v5/asset/asset-overview")

    /** One coin's chains and contract addresses. */
    fun coin(symbol: String) = BybitRequest("/v5/asset/coin/query-info", listOf("coin" to symbol.uppercase()))

    fun transactionLog(
        fromMillis: Long,
        toMillis: Long,
        cursor: String? = null,
    ): BybitRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= LOG_WINDOW_MILLIS)
        return BybitRequest(
            "/v5/account/transaction-log",
            listOf("accountType" to "UNIFIED", "startTime" to "$fromMillis", "endTime" to "$toMillis", "limit" to "50") + cursorOf(cursor),
        )
    }

    /** Times are whole seconds; [fromMillis] rounds down and [toMillis] up, so nothing at the edges is missed. */
    fun fundingHistory(
        fromMillis: Long,
        toMillis: Long,
        cursor: String? = null,
    ): BybitRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= LOG_WINDOW_MILLIS)
        val from = fromMillis / 1000
        val to = minOf((toMillis + 999) / 1000, from + LOG_WINDOW_MILLIS / 1000)
        return BybitRequest(
            "/v5/asset/fundinghistory",
            listOf("createTimeFrom" to "$from", "createTimeTo" to "$to", "limit" to "100") + cursorOf(cursor),
        )
    }

    fun deposits(
        fromMillis: Long,
        toMillis: Long,
        cursor: String? = null,
    ): BybitRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= RECORD_WINDOW_MILLIS)
        return BybitRequest(
            "/v5/asset/deposit/query-record",
            listOf("startTime" to "$fromMillis", "endTime" to "$toMillis", "limit" to "50") + cursorOf(cursor),
        )
    }

    /** On-chain and off-chain withdrawals. */
    fun withdrawals(
        fromMillis: Long,
        toMillis: Long,
        cursor: String? = null,
    ): BybitRequest {
        require(toMillis > fromMillis && toMillis - fromMillis <= RECORD_WINDOW_MILLIS)
        return BybitRequest(
            "/v5/asset/withdraw/query-record",
            listOf("withdrawType" to "2", "startTime" to "$fromMillis", "endTime" to "$toMillis", "limit" to "50") + cursorOf(cursor),
        )
    }

    /** Convert history, newest first, in pages numbered from 1. */
    fun converts(page: Int) =
        BybitRequest("/v5/asset/exchange/query-convert-history", listOf("index" to "$page", "limit" to "100")).also { require(page >= 1) }

    /** Message to sign for a GET request: timestamp, key, receive window and query string, concatenated. */
    fun signature(
        secret: String,
        apiKey: String,
        timestampMillis: Long,
        request: BybitRequest,
    ): String =
        hmacSha256(
            secret.encodeToByteArray(),
            "$timestampMillis$apiKey$RECV_WINDOW${request.queryString}".encodeToByteArray(),
        ).toHex()

    /** Headers for a signed request. */
    fun headers(
        secret: String,
        apiKey: String,
        timestampMillis: Long,
        request: BybitRequest,
    ): List<Pair<String, String>> =
        listOf(
            "X-BAPI-API-KEY" to apiKey,
            "X-BAPI-TIMESTAMP" to "$timestampMillis",
            "X-BAPI-RECV-WINDOW" to "$RECV_WINDOW",
            "X-BAPI-SIGN" to signature(secret, apiKey, timestampMillis, request),
        )

    /** The `result` object of a successful response; any other `retCode` throws [BybitException]. */
    fun result(payload: String): JsonObject {
        val root = json.parseToJsonElement(payload).jsonObject
        val code = root.int("retCode") ?: throw BybitException(-1, "Unexpected response from Bybit")
        if (code != 0) throw BybitException(code, root.text("retMsg").ifBlank { "Bybit error $code" })
        return root["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun parseServerTime(payload: String): Long =
        requireNotNull(result(payload).text("timeSecond").toLongOrNull()) { "Unexpected time from Bybit" } * 1000

    fun parseKeyInfo(payload: String): BybitKeyInfo {
        val result = result(payload)
        val expires =
            result
                .text("expiredAt")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                ?.takeIf { it > 0 }
        return BybitKeyInfo(
            uid = result.text("userID").also { require(it.isNotBlank() && it.all(Char::isDigit)) { "Unexpected key information" } },
            readOnly = result.int("readOnly") == 1,
            unified = result.int("uta") == 1,
            ipBound =
                (result["ips"] as? JsonArray)?.any {
                    (it as? JsonPrimitive)?.content.orEmpty().let { ip ->
                        ip.isNotBlank() && ip != "*"
                    }
                } ==
                    true,
            expiresAtMillis = expires,
        )
    }

    fun parseUnifiedBalance(payload: String): List<BybitBalance> =
        result(payload).array("list").flatMap { account ->
            account.jsonObject.array("coin").mapNotNull { coin ->
                val o = coin.jsonObject
                decimal(o.text("walletBalance"))?.let { BybitBalance("UNIFIED", o.text("coin"), it) }
            }
        }

    fun parseFundingBalance(payload: String): List<BybitBalance> =
        result(payload).array("balance").mapNotNull { coin ->
            val o = coin.jsonObject
            decimal(o.text("walletBalance"))?.let { BybitBalance("FUND", o.text("coin"), it) }
        }

    /**
     * Coin balances of the account types other than Funding and Unified Trading (Earn, bots, copy trading, loans, …),
     * which have no history of their own.
     */
    fun parseOverview(payload: String): List<BybitBalance> =
        result(payload).array("list").flatMap { element ->
            val account = element.jsonObject
            val type = account.text("accountType")
            if (type == "FundingAccount" || type == "UnifiedTradingAccount") return@flatMap emptyList()
            val details = account.array("coinDetail") + account.array("categories").flatMap { it.jsonObject.array("coinDetail") }
            details.mapNotNull { detail ->
                val o = detail.jsonObject
                decimal(o.text("equity"))?.let { BybitBalance(type, o.text("coin"), it) }
            }
        }

    /** Each coin's contracts: the chains it can be deposited on, with the token contract where there is one. */
    fun parseCoinContracts(payload: String): Map<String, List<ExchangeContract>> =
        result(payload)
            .array("rows")
            .associate { row ->
                val o = row.jsonObject
                o.text("coin") to
                    o.array("chains").mapNotNull { element ->
                        val chain = element.jsonObject
                        chain
                            .text("contractAddress")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { ExchangeContract(chain.text("chain"), chain.text("chainType"), it) }
                    }
            }.filterKeys { it.isNotBlank() }

    fun parseTransactionLog(payload: String): BybitPage<BybitLogEntry> =
        result(payload).let { result -> BybitPage(result.array("list").map { logEntry(it.jsonObject) }, next(result)) }

    fun parseFundingHistory(payload: String): BybitPage<BybitFundingEntry> =
        result(payload).let { result -> BybitPage(result.array("list").map { fundingEntry(it.jsonObject) }, next(result)) }

    fun parseDeposits(payload: String): BybitPage<BybitDeposit> =
        result(payload).let { result -> BybitPage(result.array("rows").map { deposit(it.jsonObject) }, next(result)) }

    fun parseWithdrawals(payload: String): BybitPage<BybitWithdrawal> =
        result(payload).let { result -> BybitPage(result.array("rows").map { withdrawal(it.jsonObject) }, next(result)) }

    fun parseConverts(payload: String): List<BybitConvert> = result(payload).array("list").map { convert(it.jsonObject) }

    /** A row saved as [BybitLogEntry.raw] and the like, parsed again. */
    fun row(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    fun logEntry(o: JsonObject) =
        BybitLogEntry(
            id = o.text("id"),
            type = o.text("type"),
            category = o.text("category"),
            symbol = o.text("symbol"),
            currency = o.text("currency").also { require(it.isNotBlank()) { "Log entry without coin" } },
            change = required(o, "change"),
            cashFlow = decimal(o.text("cashFlow")) ?: ZERO,
            funding = decimal(o.text("funding")) ?: ZERO,
            fee = decimal(o.text("fee")) ?: ZERO,
            balanceAfter = required(o, "cashBalance"),
            tradeId = o.text("tradeId"),
            orderId = o.text("orderId"),
            atMillis = millis(o, "transactionTime"),
            raw = o.toString(),
        )

    fun fundingEntry(o: JsonObject): BybitFundingEntry {
        val amount = required(o, "txnAmt")
        val direction = o.text("ioDirection")
        require(direction == "I" || direction == "O") { "Unexpected funding direction" }
        return BybitFundingEntry(
            id = o.text("currcCursor").also { require(it.isNotBlank()) { "Funding entry without ID" } },
            currency = o.text("currency").also { require(it.isNotBlank()) { "Funding entry without coin" } },
            change = if (direction == "O" && amount.coefficient.sign > 0) ExactDecimal(-amount.coefficient, amount.scale) else amount,
            balanceAfter = required(o, "afterAmt"),
            atMillis = millis(o, "createTime") * 1000,
            business = o.text("showBusiTypeEn"),
            description = o.text("descriptionEn"),
            raw = o.toString(),
        )
    }

    fun deposit(o: JsonObject) =
        BybitDeposit(
            id = o.text("id"),
            coin = o.text("coin"),
            chain = o.text("chain"),
            amount = required(o, "amount"),
            txId = o.text("txID"),
            status = o.int("status") ?: 0,
            atMillis = millis(o, "successAt"),
            raw = o.toString(),
        )

    fun withdrawal(o: JsonObject) =
        BybitWithdrawal(
            id = o.text("withdrawId"),
            coin = o.text("coin"),
            chain = o.text("chain"),
            amount = required(o, "amount"),
            fee = decimal(o.text("withdrawFee")) ?: ZERO,
            txId = o.text("txID"),
            status = o.text("status"),
            atMillis = millis(o, "createTime"),
            onChain = o.int("withdrawType") != 1,
            raw = o.toString(),
        )

    fun convert(o: JsonObject) =
        BybitConvert(
            id = o.text("exchangeTxId"),
            fromCoin = o.text("fromCoin"),
            fromAmount = required(o, "fromAmount"),
            toCoin = o.text("toCoin"),
            toAmount = required(o, "toAmount"),
            status = o.text("exchangeStatus"),
            atMillis = millis(o, "createdAt"),
            raw = o.toString(),
        )

    private val ZERO = ExactDecimal.parse("0")

    private fun cursorOf(cursor: String?) = if (cursor.isNullOrEmpty()) emptyList() else listOf("cursor" to cursor)

    private fun next(result: JsonObject) = result.text("nextPageCursor").takeIf { it.isNotEmpty() }

    private fun JsonObject.text(name: String): String =
        when (val value = this[name]) {
            null, is JsonNull -> ""
            is JsonPrimitive -> value.content
            else -> ""
        }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

    private fun decimal(text: String): ExactDecimal? =
        text
            .takeIf {
                it.isNotBlank()
            }?.let { runCatching { ExactDecimal.parse(it) }.getOrNull() }

    private fun required(
        o: JsonObject,
        name: String,
    ): ExactDecimal = requireNotNull(decimal(o.text(name))) { "Unexpected $name from Bybit" }

    private fun millis(
        o: JsonObject,
        name: String,
    ): Long = requireNotNull(o.text(name).toLongOrNull()) { "Unexpected $name from Bybit" }
}
