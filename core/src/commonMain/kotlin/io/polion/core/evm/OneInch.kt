package io.polion.core.evm

import io.polion.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** One transaction in 1inch's history of an address: what Polion uses from it. */
data class OneInchEvent(
    val hash: String,
    val blockNumber: Long,
    val timestampMillis: Long,
    /** 1inch reports native coin paid to the address by a contract in this transaction; the trace gives the amount. */
    val contractPayment: Boolean,
    /** Token contracts the event moves. */
    val contracts: Set<String>,
)

/**
 * 1inch's History and Traces APIs, used only to recover native coin paid by contracts on chains where Alchemy does not
 * list such payments, and to learn which tokens 1inch lists.
 *
 * - **History** (`/history/v2.0/history/{address}/events`) finds the transactions. 1inch leaves out tokens it rates as
 *   scams. Its fees and amounts are not used: tests against receipts found fees that differ from them, and a missing
 *   outgoing amount.
 * - **Traces** (`/traces/v1.0/chain/{chain}/block-trace/{block}/tx-hash/{hash}`) give each call with its value, so
 *   every payment is read from the executed calls, with its call path as identity.
 *
 * Paths are relative to `https://api.1inch.com`; the host adds the key as a bearer token.
 */
object OneInch {
    const val BASE_URL = "https://api.1inch.com"
    const val HISTORY_LIMIT = 10_000
    const val MAX_HISTORY_PAGES = 20

    /** Provenance of transfers read from 1inch traces. */
    const val SOURCE = "1inch-trace"

    /** Chains 1inch's History and Traces APIs serve, among those Polion tracks. */
    val CHAINS = setOf(1L, 56L, 137L, 10L, 42161L, 100L, 8453L)

    fun historyPath(
        address: String,
        chainId: Long,
        beforeMillis: Long? = null,
    ): String {
        require(chainId in CHAINS)
        return "/history/v2.0/history/${evmAddress(address)}/events?chainId=$chainId&limit=$HISTORY_LIMIT" +
            (beforeMillis?.let { "&toTimestampMs=$it" } ?: "")
    }

    fun tracePath(
        chainId: Long,
        blockNumber: Long,
        hash: String,
    ): String {
        require(chainId in CHAINS && blockNumber >= 0 && hash.matches(HASH))
        return "/traces/v1.0/chain/$chainId/block-trace/$blockNumber/tx-hash/$hash"
    }

    /** Events for [address], newest first as 1inch returns them. */
    fun parseHistory(
        payload: String,
        address: String,
    ): List<OneInchEvent> =
        Json.parseToJsonElement(payload).jsonObject["items"]!!.jsonArray.map { element ->
            val item = element.jsonObject
            val details = item["details"]!!.jsonObject
            val hash = details.text("txHash")!!.lowercase()
            require(hash.matches(HASH)) { "Invalid transaction hash" }
            val sender = details.text("fromAddress")?.lowercase()
            val actions = (details["tokenActions"] as? JsonArray).orEmpty().map { it.jsonObject }
            OneInchEvent(
                hash,
                details.text("blockNumber")!!.toLong(),
                item.text("timeMs")!!.toLong(),
                actions.any { action ->
                    action.text("standard") == "Native" &&
                        action.text("toAddress")?.lowercase() == address &&
                        action.text("fromAddress")?.lowercase() != sender
                },
                actions
                    .filter { it.text("standard") != "Native" }
                    .mapNotNull { action -> action.text("address")?.lowercase()?.takeIf { it.matches(ADDRESS) } }
                    .toSet(),
            )
        }

    /**
     * Native coin moved to or from [address] by calls inside the transaction, in call order. The top-level call is left
     * out, since Alchemy lists it. A call counts only if it and every call above it succeeded; delegate and static calls
     * move no value.
     */
    fun parseTrace(
        payload: String,
        hash: String,
        blockNumber: Long,
        timestampMillis: Long,
        address: String,
    ): List<EvmInternalTransfer> {
        val root = Json.parseToJsonElement(payload).jsonObject["transactionTrace"]!!.jsonObject
        require(root.text("txHash")?.lowercase() == hash) { "Trace is for another transaction" }
        val transfers = mutableListOf<EvmInternalTransfer>()
        var ordinal = 0

        fun walk(
            call: JsonObject,
            parentOk: Boolean,
            top: Boolean,
        ) {
            val ok = parentOk && succeeded(call)
            val value = call.text("value")?.let(::evmQuantity) ?: ExactInteger.ZERO
            val type = call.text("type")
            val from = call.text("from")?.lowercase()
            val to = call.text("to")?.lowercase()
            if (!top && value.sign > 0 && (type == "CALL" || type == "SELFDESTRUCT") && (from == address || to == address)) {
                transfers +=
                    EvmInternalTransfer(
                        hash,
                        blockNumber,
                        ordinal,
                        timestampMillis,
                        evmAddress(requireNotNull(from)),
                        to?.let(::evmAddress),
                        value,
                        ok,
                        SOURCE,
                    )
            }
            if (!top) ordinal++
            (call["calls"] as? JsonArray).orEmpty().forEach { walk(it.jsonObject, ok, top = false) }
        }
        walk(root, parentOk = true, top = true)
        return transfers
    }

    private fun succeeded(call: JsonObject): Boolean {
        val success = (call["success"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val status = call.text("status")?.uppercase().orEmpty()
        return success != "0" && success != "false" && FAILED.none { it in status }
    }

    private val FAILED = listOf("REVERT", "INVALID", "ERROR", "OUT_OF", "FAIL")
    private val HASH = Regex("0x[0-9a-f]{64}")
    private val ADDRESS = Regex("0x[0-9a-f]{40}")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
