package io.polion.core.bitcoin

import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryFetcher
import io.polion.core.connectors.HistoryPage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.connectors.RawRecordDecoder
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.FeeAllocationReason
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.ledger.UnallocatedFee
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.NetworkId
import io.polion.core.model.SourceRecordId
import io.polion.core.storage.RawObservation
import io.polion.core.storage.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

val BITCOIN_MAINNET = NetworkId("bitcoin-mainnet")
val BITCOIN_ASSET = AssetId("bitcoin:btc")

/** Registry identity of the Esplora history connector for Bitcoin mainnet. */
const val BITCOIN_CONNECTOR_ID = "bitcoin-esplora"

fun interface EsploraTransport {
    fun get(path: String): String
}

/** Requests disclose the watched address to the selected Esplora server. */
class EsploraHistoryFetcher(
    addressByAccount: Map<AccountId, String>,
    private val transport: EsploraTransport,
    private val retrievedAt: () -> String,
) : HistoryFetcher {
    private val addresses = addressByAccount.mapValues { bitcoinMainnetAddress(it.value) }

    override fun fetch(request: HistoryRequest): HistoryPage {
        require(request.network == BITCOIN_MAINNET)
        val address = addresses.getValue(request.account)
        val prefix = "/address/$address/txs"
        val partial = request.cursor?.startsWith("partial:") == true
        val cursor = request.cursor?.substringAfter(':')
        require(cursor == null || cursor.matches(Regex("[0-9a-f]{64}")))
        val mempool = if (cursor == null) parseArray(transport.get("$prefix/mempool")) else JsonArray(emptyList())
        require(mempool.size <= 50)
        val chain = parseArray(transport.get("$prefix/chain" + (cursor?.let { "/$it" } ?: "")))
        require(chain.size <= 25)
        val txs = mempool + chain
        val parsed = txs.map { parseTransaction(it.jsonObject) }
        require(parsed.take(mempool.size).all { !it.confirmed })
        require(parsed.drop(mempool.size).all { it.confirmed })
        require(parsed.all { tx -> (tx.inputs + tx.outputs).any { it.address == address } }) { "Unrelated transaction in address history" }
        val ids = parsed.map { it.id }
        require(ids.distinct().size == ids.size) { "Duplicate transaction in page" }
        val isPartial = partial || mempool.size == 50
        val next = if (chain.size == 25) (if (isPartial) "partial:" else "chain:") + ids.last() else null
        val observations =
            txs.mapIndexed { index, tx ->
                val payload = tx.toString()
                RawObservation(
                    "bitcoin:${ids[index]}",
                    "bitcoin",
                    ids[index],
                    request.network.value,
                    request.source.value,
                    retrievedAt(),
                    payload,
                    "esplora-1",
                    sha256(payload),
                    "esplora",
                )
            }
        return HistoryPage(observations, next, if (next == null && !isPartial) Coverage.COMPLETE else Coverage.PARTIAL)
    }
}

private fun parseArray(value: String): JsonArray = Json.parseToJsonElement(value).jsonArray

private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.amount(key: String): ExactInteger {
    val number = getValue(key).jsonPrimitive
    require(!number.isString)
    val text = number.content
    require(text.matches(Regex("0|[1-9][0-9]*")))
    return ExactInteger.parse(text)
}

private data class Output(
    val address: String?,
    val value: ExactInteger,
)

private data class Transaction(
    val id: String,
    val inputs: List<Output>,
    val outputs: List<Output>,
    val fee: ExactInteger,
    val confirmed: Boolean,
    val time: Long,
)

private fun output(value: JsonObject): Output = Output(value["scriptpubkey_address"]?.jsonPrimitive?.contentOrNull, value.amount("value"))

private fun parseTransaction(value: JsonObject): Transaction {
    val id = value.str("txid")
    require(id.matches(Regex("[0-9a-f]{64}")))
    val vin = value.getValue("vin").jsonArray
    require(vin.isNotEmpty())
    val coinbase = vin.any { it.jsonObject["is_coinbase"]?.jsonPrimitive?.boolean == true }
    require(
        !coinbase ||
            vin.size == 1 &&
            vin
                .single()
                .jsonObject["is_coinbase"]
                ?.jsonPrimitive
                ?.boolean == true,
    )
    val inputs =
        vin
            .map { entry ->
                val input = entry.jsonObject
                if (input["is_coinbase"]?.jsonPrimitive?.boolean == true) null else output(input.obj("prevout"))
            }.filterNotNull()
    val outputs = value.getValue("vout").jsonArray.map { output(it.jsonObject) }
    require(outputs.isNotEmpty())
    val maxMoney = ExactInteger.parse("2100000000000000")

    fun total(items: List<Output>): ExactInteger =
        items
            .fold(ExactInteger.ZERO) { sum, item ->
                require(item.value <= maxMoney)
                sum + item.value
            }.also { require(it <= maxMoney) }
    val inputTotal = total(inputs)
    val outputTotal = total(outputs)
    val fee = value.amount("fee")
    require(fee <= maxMoney)
    require(if (coinbase) fee == ExactInteger.ZERO else inputTotal - outputTotal == fee) { "Inconsistent transaction amounts" }
    val status = value.obj("status")
    val confirmed = status.getValue("confirmed").jsonPrimitive.boolean
    val time = if (confirmed) status.getValue("block_time").jsonPrimitive.long else 0L
    require(time >= 0 && time <= Long.MAX_VALUE / 1000)
    return Transaction(id, inputs, outputs, fee, confirmed, time * 1000)
}

/** Decodes the transaction against all known owned addresses, so change is counted once. */
class BitcoinDecoder(
    ownedAddresses: Map<String, AccountId>,
) : RawRecordDecoder {
    private val owners = ownedAddresses.mapKeys { bitcoinMainnetAddress(it.key) }
    override val version = "bitcoin-esplora-2"

    companion object {
        /** Decoder versions whose activity identity and balance semantics belong to this connector. */
        internal val compatibleVersions = setOf("bitcoin-esplora-1", "bitcoin-esplora-2")
    }

    override fun decode(observation: RawObservation): List<Activity> {
        require(observation.network == BITCOIN_MAINNET.value && observation.namespace == "bitcoin")
        val tx = parseTransaction(Json.parseToJsonElement(observation.payload).jsonObject)
        require(observation.externalId == tx.id && observation.id == "bitcoin:${tx.id}")
        val deltas = mutableMapOf<AccountId, ExactInteger>()

        fun add(
            output: Output,
            negative: Boolean,
        ) {
            val account = output.address?.let(owners::get) ?: return
            deltas[account] = (deltas[account] ?: ExactInteger.ZERO) + if (negative) -output.value else output.value
        }
        tx.inputs.forEach { add(it, true) }
        tx.outputs.forEach { add(it, false) }
        val inputOwners = tx.inputs.map { it.address?.let(owners::get) }
        val feeAccount =
            inputOwners.singleOrNull()?.let { it } ?: inputOwners.firstOrNull()?.takeIf { owner -> inputOwners.all { it == owner } }
        val trackedInputAccounts = inputOwners.filterNotNull().toSet()
        val unallocatedFees =
            if (feeAccount == null && tx.fee.sign > 0 && trackedInputAccounts.isNotEmpty()) {
                listOf(
                    UnallocatedFee(
                        AtomicAmount(BITCOIN_ASSET, tx.fee),
                        if (inputOwners.any { it == null }) {
                            FeeAllocationReason.MIXED_TRACKED_EXTERNAL_PAYERS
                        } else {
                            FeeAllocationReason.MULTIPLE_TRACKED_PAYERS
                        },
                        trackedInputAccounts,
                    ),
                )
            } else {
                emptyList()
            }
        if (feeAccount != null && tx.fee.sign > 0) deltas[feeAccount] = deltas.getValue(feeAccount) + tx.fee
        val movements =
            deltas
                .toList()
                .sortedBy { it.first.value }
                .filter { it.second.sign != 0 }
                .map { (account, amount) ->
                    Movement(
                        EventId("bitcoin:${tx.id}:${account.value}:transfer"),
                        account,
                        AtomicAmount(BITCOIN_ASSET, amount),
                        MovementRole.TRANSFER,
                    )
                }.toMutableList()
        if (feeAccount != null &&
            tx.fee.sign > 0
        ) {
            movements.add(
                Movement(
                    EventId("bitcoin:${tx.id}:${feeAccount.value}:fee"),
                    feeAccount,
                    AtomicAmount(BITCOIN_ASSET, -tx.fee),
                    MovementRole.FEE,
                ),
            )
        }
        if (movements.isEmpty()) return emptyList()
        val kind =
            when {
                inputOwners.none { it != null } -> ActivityKind.DEPOSIT
                tx.outputs.all { it.address?.let(owners::get) != null } -> ActivityKind.TRANSFER
                else -> ActivityKind.PAYMENT
            }
        val order = observation.context.split(':')
        if (order.first() == "bitcoin-order") require(tx.confirmed && order.size == 4)
        val blockOrder =
            if (order.first() == "bitcoin-order") {
                val height = order[1].toLong()
                val index = order[2].toInt()
                require(height >= 0 && index >= 0 && order[3].matches(Regex("[0-9a-f]{64}")))
                val status =
                    Json
                        .parseToJsonElement(observation.payload)
                        .jsonObject
                        .getValue("status")
                        .jsonObject
                require(
                    status.getValue("block_hash").jsonPrimitive.content == order[3] &&
                        status.getValue("block_height").jsonPrimitive.long == height,
                )
                "bitcoin-chain:${height.toString().padStart(20, '0')}:${index.toString().padStart(10, '0')}:${tx.id}"
            } else {
                tx.id
            }
        val sources = mutableSetOf(SourceRecordId(observation.id))
        if (blockOrder != tx.id) sources.add(SourceRecordId("bitcoin-block:${order[3]}"))
        return listOf(
            Activity(
                ActivityId("bitcoin:${tx.id}"),
                tx.time,
                blockOrder,
                kind,
                if (tx.confirmed) ActivityStatus.CONFIRMED else ActivityStatus.PENDING,
                movements,
                sources,
                version,
                unallocatedFees = unallocatedFees,
            ),
        )
    }
}
