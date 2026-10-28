package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Blocks a coinbase output must wait before it can be spent. */
const val BITCOIN_COINBASE_MATURITY = 100L

private val TXID = Regex("[0-9a-f]{64}")
private val MAX_MONEY = ExactInteger.parse("2100000000000000")
private const val MAX_VOUT = 0xffffffffL

data class BitcoinOutpoint(
    val txid: String,
    val vout: Long,
) : Comparable<BitcoinOutpoint> {
    init {
        require(txid.matches(TXID) && vout in 0..MAX_VOUT) { "Invalid outpoint" }
    }

    override fun compareTo(other: BitcoinOutpoint): Int = compareValuesBy(this, other, { it.txid }, { it.vout })

    override fun toString(): String = "$txid:$vout"
}

/** Output metadata as reported by Esplora; [address] is absent for nonstandard, bare-key and data outputs. */
data class BitcoinScriptOutput(
    val value: ExactInteger,
    val scriptPubKey: String?,
    val scriptType: String?,
    val address: String?,
)

/** [spends] and [prevout] are null only for a coinbase input. */
data class BitcoinTxInput(
    val index: Int,
    val spends: BitcoinOutpoint?,
    val prevout: BitcoinScriptOutput?,
    val sequence: Long?,
)

data class BitcoinTxOutput(
    val outpoint: BitcoinOutpoint,
    val output: BitcoinScriptOutput,
)

data class BitcoinBlockAnchor(
    val height: Long,
    val hash: String,
) {
    init {
        require(height >= 0 && hash.matches(TXID)) { "Invalid block anchor" }
    }
}

data class BitcoinGraphTransaction(
    val id: String,
    val inputs: List<BitcoinTxInput>,
    val outputs: List<BitcoinTxOutput>,
    val fee: ExactInteger,
    val coinbase: Boolean,
    val block: BitcoinBlockAnchor?,
    val blockTime: Long?,
) {
    val confirmed: Boolean get() = block != null

    /** BIP125 opt-in signal; a full-RBF mempool may replace transactions without it. */
    val signalsReplacement: Boolean get() = inputs.any { it.sequence != null && it.sequence < 0xfffffffeL }
}

/** Parses one Esplora `/tx` or address-history payload, preserving outpoints and script metadata. */
fun parseBitcoinGraphTransaction(payload: String): BitcoinGraphTransaction {
    val value = Json.parseToJsonElement(payload).jsonObject
    val id = value.getValue("txid").jsonPrimitive.content
    require(id.matches(TXID)) { "Invalid transaction id" }
    val vin = value.getValue("vin").jsonArray
    require(vin.isNotEmpty())
    val coinbase = vin.any { it.jsonObject.flag("is_coinbase") }
    require(!coinbase || vin.size == 1) { "Coinbase transaction must have one input" }
    val inputs =
        vin.mapIndexed { index, entry ->
            val input = entry.jsonObject
            val sequence = input["sequence"]?.jsonPrimitive?.long?.also { require(it in 0..MAX_VOUT) }
            if (input.flag("is_coinbase")) {
                BitcoinTxInput(index, null, null, sequence)
            } else {
                BitcoinTxInput(
                    index,
                    BitcoinOutpoint(input.getValue("txid").jsonPrimitive.content, input.getValue("vout").jsonPrimitive.long),
                    scriptOutput(input.getValue("prevout").jsonObject),
                    sequence,
                )
            }
        }
    require(inputs.mapNotNull { it.spends }.let { it.distinct().size == it.size }) { "Transaction spends an outpoint twice" }
    val outputs =
        value.getValue("vout").jsonArray.mapIndexed { index, entry ->
            BitcoinTxOutput(BitcoinOutpoint(id, index.toLong()), scriptOutput(entry.jsonObject))
        }
    require(outputs.isNotEmpty())
    val inputTotal = total(inputs.mapNotNull { it.prevout?.value })
    val outputTotal = total(outputs.map { it.output.value })
    val fee = value.satoshis("fee")
    require(fee <= MAX_MONEY)
    require(if (coinbase) fee == ExactInteger.ZERO else inputTotal - outputTotal == fee) { "Inconsistent transaction amounts" }
    val status = value.getValue("status").jsonObject
    val confirmed = status.getValue("confirmed").jsonPrimitive.boolean
    require(confirmed || !coinbase) { "Unconfirmed coinbase transaction" }
    val block =
        if (confirmed) {
            BitcoinBlockAnchor(status.getValue("block_height").jsonPrimitive.long, status.getValue("block_hash").jsonPrimitive.content)
        } else {
            null
        }
    val time = if (confirmed) status["block_time"]?.jsonPrimitive?.long?.also { require(it >= 0) } else null
    return BitcoinGraphTransaction(id, inputs, outputs, fee, coinbase, block, time)
}

private fun JsonObject.flag(key: String): Boolean = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.boolean == true

private fun JsonObject.satoshis(key: String): ExactInteger {
    val number = getValue(key).jsonPrimitive
    require(!number.isString && number.content.matches(Regex("0|[1-9][0-9]*"))) { "Invalid amount" }
    return ExactInteger.parse(number.content)
}

private fun scriptOutput(value: JsonObject): BitcoinScriptOutput {
    val amount = value.satoshis("value")
    require(amount <= MAX_MONEY)
    return BitcoinScriptOutput(
        amount,
        value["scriptpubkey"]?.jsonPrimitive?.contentOrNull,
        value["scriptpubkey_type"]?.jsonPrimitive?.contentOrNull,
        value["scriptpubkey_address"]?.jsonPrimitive?.contentOrNull,
    )
}

private fun total(values: List<ExactInteger>): ExactInteger =
    values.fold(ExactInteger.ZERO) { sum, item -> sum + item }.also {
        require(
            it <= MAX_MONEY,
        )
    }

enum class BitcoinTransactionState {
    CONFIRMED,
    PENDING,

    /** Unconfirmed and in conflict, directly or through an ancestor, with another unconfirmed transaction. */
    CONFLICTING,

    /** Unconfirmed and in conflict, directly or through an ancestor, with a confirmed transaction. */
    REPLACED,
}

enum class BitcoinOutputStatus {
    UNSPENT,

    /** Spent by a confirmed transaction. */
    SPENT,

    /** Spent by a non-conflicting unconfirmed transaction. */
    SPEND_PENDING,

    /** Unspent; only competing unconfirmed transactions spend it. */
    SPEND_CONFLICTING,

    /** Created by a conflicting unconfirmed transaction; excluded from balances until resolved. */
    CONFLICTING,

    /** Created by a replaced transaction; it cannot exist on the active chain. */
    INVALIDATED,
}

data class BitcoinOwnedOutput(
    val outpoint: BitcoinOutpoint,
    val account: AccountId,
    val address: String,
    val value: ExactInteger,
    val scriptType: String?,
    val block: BitcoinBlockAnchor?,
    val coinbase: Boolean,
    val spentBy: List<String>,
    val status: BitcoinOutputStatus,
) {
    val confirmed: Boolean get() = block != null
    val unspent: Boolean get() = status == BitcoinOutputStatus.UNSPENT || status == BitcoinOutputStatus.SPEND_CONFLICTING

    /** Whether a coinbase output can be spent in the block after [tipHeight]; always true for other outputs. */
    fun mature(tipHeight: Long): Boolean = !coinbase || block != null && tipHeight + 1 >= block.height + BITCOIN_COINBASE_MATURITY
}

/** Transactions spending the same outpoint; [confirmedSpender] wins and the others are replaced. */
data class BitcoinSpendConflict(
    val outpoint: BitcoinOutpoint,
    val spenders: List<String>,
    val confirmedSpender: String?,
)

data class BitcoinAccountUtxoBalance(
    /** Confirmed outputs not spent by a confirmed transaction. */
    val confirmed: ExactInteger,
    /** The unspent set, including unconfirmed effects except conflicting ones. */
    val projected: ExactInteger,
    val unconfirmedIncoming: ExactInteger,
    /** Confirmed unspent coinbase value not yet mature at the supplied tip; zero without a tip. */
    val immatureCoinbase: ExactInteger,
)

/**
 * Outpoint/spend graph reconstructed from stored Esplora transaction payloads.
 *
 * Inputs must be the current observation of each transaction; a different payload for the same txid is rejected.
 * Supply all transactions touching every owned address; spends of owned outputs whose creating transaction is
 * absent are reported in [missingParents] because the reconstruction is then incomplete.
 */
class BitcoinUtxoGraph private constructor(
    val ownedAddresses: Map<String, AccountId>,
    val transactions: Map<String, BitcoinGraphTransaction>,
    val states: Map<String, BitcoinTransactionState>,
    val conflicts: List<BitcoinSpendConflict>,
    val ownedOutputs: List<BitcoinOwnedOutput>,
    val missingParents: List<BitcoinOutpoint>,
) {
    val unspent: List<BitcoinOwnedOutput> get() = ownedOutputs.filter { it.unspent }
    val unconfirmedOutputs: List<BitcoinOwnedOutput> get() = unspent.filter { !it.confirmed }

    fun unspent(account: AccountId): List<BitcoinOwnedOutput> = unspent.filter { it.account == account }

    /** Replaced transaction ids mapped to the confirmed transactions that conflict with them directly. */
    val replacements: Map<String, List<String>>
        get() =
            conflicts
                .filter { it.confirmedSpender != null }
                .flatMap { conflict -> (conflict.spenders - conflict.confirmedSpender!!).map { it to conflict.confirmedSpender } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.distinct().sorted() }

    fun balances(tipHeight: Long? = null): Map<AccountId, BitcoinAccountUtxoBalance> =
        ownedAddresses.values.toSet().sortedBy { it.value }.associateWith { account ->
            val owned = ownedOutputs.filter { it.account == account }
            val confirmed =
                owned.filter { output ->
                    output.confirmed && output.spentBy.none { states[it] == BitcoinTransactionState.CONFIRMED }
                }
            val unspent = owned.filter { it.unspent }
            BitcoinAccountUtxoBalance(
                sum(confirmed),
                sum(unspent),
                sum(unspent.filter { !it.confirmed }),
                if (tipHeight == null) ExactInteger.ZERO else sum(confirmed.filter { !it.mature(tipHeight) }),
            )
        }

    companion object {
        fun build(
            payloads: Collection<String>,
            ownedAddresses: Map<String, AccountId>,
        ): BitcoinUtxoGraph {
            val owners = ownedAddresses.mapKeys { bitcoinMainnetAddress(it.key) }
            val byId = mutableMapOf<String, Pair<String, BitcoinGraphTransaction>>()
            payloads.forEach { payload ->
                val tx = parseBitcoinGraphTransaction(payload)
                val previous = byId[tx.id]
                require(previous == null || previous.first == payload) { "Transaction has conflicting observations" }
                byId[tx.id] = payload to tx
            }
            val transactions = byId.mapValues { it.value.second }.toSortedMap()
            val spenders = mutableMapOf<BitcoinOutpoint, MutableList<String>>()
            transactions.values.forEach { tx ->
                tx.inputs.forEach { input -> input.spends?.let { spenders.getOrPut(it) { mutableListOf() }.add(tx.id) } }
            }
            transactions.values.forEach { tx ->
                tx.inputs.forEach { input ->
                    val spent = input.spends ?: return@forEach
                    val parent = transactions[spent.txid] ?: return@forEach
                    require(spent.vout < parent.outputs.size && parent.outputs[spent.vout.toInt()].output.sameCoin(input.prevout)) {
                        "Input prevout disagrees with its creating transaction"
                    }
                    val parentBlock = parent.block
                    if (tx.block != null) {
                        require(
                            parentBlock != null && parentBlock.height <= tx.block.height,
                        ) { "Confirmed transaction spends a later output" }
                    }
                    if (parent.coinbase && tx.block != null && parentBlock != null) {
                        require(tx.block.height >= parentBlock.height + BITCOIN_COINBASE_MATURITY) { "Immature coinbase spend" }
                    }
                }
            }
            val children = mutableMapOf<String, MutableSet<String>>()
            spenders.forEach { (outpoint, ids) ->
                if (outpoint.txid in
                    transactions
                ) {
                    children.getOrPut(outpoint.txid) { mutableSetOf() }.addAll(ids)
                }
            }
            val conflicts =
                spenders
                    .filter { it.value.size > 1 }
                    .toSortedMap()
                    .map { (outpoint, ids) ->
                        val confirmed = ids.filter { transactions.getValue(it).confirmed }
                        require(confirmed.size <= 1) { "Provider reported conflicting confirmed transactions" }
                        BitcoinSpendConflict(outpoint, ids.sorted(), confirmed.singleOrNull())
                    }
            val states =
                transactions
                    .mapValues {
                        if (it.value.confirmed) BitcoinTransactionState.CONFIRMED else BitcoinTransactionState.PENDING
                    }.toMutableMap()

            fun mark(
                roots: Collection<String>,
                state: BitcoinTransactionState,
            ) {
                val queue = ArrayDeque(roots)
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    val current = states.getValue(id)
                    require(current != BitcoinTransactionState.CONFIRMED) { "Confirmed transaction depends on an invalid transaction" }
                    if (current == state || current == BitcoinTransactionState.REPLACED) continue
                    states[id] = state
                    queue.addAll(children[id].orEmpty())
                }
            }
            mark(
                conflicts.filter { it.confirmedSpender != null }.flatMap { it.spenders - it.confirmedSpender!! },
                BitcoinTransactionState.REPLACED,
            )
            mark(
                conflicts
                    .filter { it.confirmedSpender == null }
                    .map { conflict -> conflict.spenders.filter { states[it] != BitcoinTransactionState.REPLACED } }
                    .filter { it.size > 1 }
                    .flatten(),
                BitcoinTransactionState.CONFLICTING,
            )
            val owned =
                transactions.values.flatMap { tx ->
                    tx.outputs.mapNotNull { output ->
                        val address = output.output.address ?: return@mapNotNull null
                        val account = owners[address] ?: return@mapNotNull null
                        val spentBy = spenders[output.outpoint].orEmpty().sorted()
                        val spentStates = spentBy.map { states.getValue(it) }
                        val status =
                            when {
                                states[tx.id] == BitcoinTransactionState.REPLACED -> BitcoinOutputStatus.INVALIDATED
                                states[tx.id] == BitcoinTransactionState.CONFLICTING -> BitcoinOutputStatus.CONFLICTING
                                BitcoinTransactionState.CONFIRMED in spentStates -> BitcoinOutputStatus.SPENT
                                BitcoinTransactionState.PENDING in spentStates -> BitcoinOutputStatus.SPEND_PENDING
                                BitcoinTransactionState.CONFLICTING in spentStates -> BitcoinOutputStatus.SPEND_CONFLICTING
                                else -> BitcoinOutputStatus.UNSPENT
                            }
                        BitcoinOwnedOutput(
                            output.outpoint,
                            account,
                            address,
                            output.output.value,
                            output.output.scriptType,
                            tx.block,
                            tx.coinbase,
                            spentBy,
                            status,
                        )
                    }
                }
            val missing =
                transactions.values
                    .flatMap { tx ->
                        tx.inputs.filter { it.prevout?.address in owners && it.spends!!.txid !in transactions }.map { it.spends!! }
                    }.distinct()
                    .sorted()
            return BitcoinUtxoGraph(owners, transactions, states, conflicts, owned, missing)
        }
    }
}

private fun BitcoinScriptOutput.sameCoin(other: BitcoinScriptOutput?): Boolean =
    other != null &&
        value == other.value &&
        address == other.address &&
        (scriptPubKey == null || other.scriptPubKey == null || scriptPubKey == other.scriptPubKey)

private fun sum(outputs: List<BitcoinOwnedOutput>): ExactInteger = outputs.fold(ExactInteger.ZERO) { total, output -> total + output.value }
