package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One unspent output reported by an Esplora `/address/{address}/utxo` response. */
data class EsploraUtxo(
    val address: String,
    val outpoint: BitcoinOutpoint,
    val value: ExactInteger,
    val block: BitcoinBlockAnchor?,
)

/** The provider's current unspent outputs for each address; the payload text is kept for provenance. */
data class EsploraUtxoSnapshot(
    val utxos: List<EsploraUtxo>,
    val payloadByAddress: Map<String, String>,
)

/**
 * Retrieves the provider's view of unspent outputs. Esplora may cap this list for heavily used addresses;
 * such a provider rejects the request, which fails the fetch rather than producing a partial set.
 */
class EsploraUtxoFetcher(
    private val transport: EsploraTransport,
) {
    fun fetch(addresses: Collection<String>): EsploraUtxoSnapshot {
        val canonical = addresses.map(::bitcoinMainnetAddress).distinct().sorted()
        val payloads = canonical.associateWith { transport.get("/address/$it/utxo") }
        return EsploraUtxoSnapshot(payloads.flatMap { (address, payload) -> parseEsploraUtxos(address, payload) }, payloads)
    }
}

fun parseEsploraUtxos(
    address: String,
    payload: String,
): List<EsploraUtxo> {
    val result =
        Json.parseToJsonElement(payload).jsonArray.map { entry ->
            val item = entry.jsonObject
            val value = item.getValue("value").jsonPrimitive
            require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*"))) { "Invalid UTXO amount" }
            val status = item.getValue("status").jsonObject
            val confirmed = status.getValue("confirmed").jsonPrimitive.boolean
            EsploraUtxo(
                address,
                BitcoinOutpoint(item.getValue("txid").jsonPrimitive.content, item.getValue("vout").jsonPrimitive.long),
                ExactInteger.parse(value.content),
                if (confirmed) {
                    BitcoinBlockAnchor(
                        status.getValue("block_height").jsonPrimitive.long,
                        status.getValue("block_hash").jsonPrimitive.content,
                    )
                } else {
                    null
                },
            )
        }
    require(result.map { it.outpoint }.distinct().size == result.size) { "Duplicate UTXO in provider response" }
    return result
}

enum class BitcoinUtxoMismatchKind {
    /** The provider reports an unspent output the reconstruction lacks or considers spent. */
    MISSING_FROM_RECONSTRUCTION,

    /** The reconstruction holds an unspent output the provider does not report. */
    MISSING_FROM_PROVIDER,
    VALUE_DIFFERS,
    ADDRESS_DIFFERS,
    CONFIRMATION_DIFFERS,

    /** The provider reports an output paying an address outside the watched set. */
    UNWATCHED_ADDRESS,
}

data class BitcoinUtxoMismatch(
    val kind: BitcoinUtxoMismatchKind,
    val outpoint: BitcoinOutpoint,
    val reconstructed: BitcoinOwnedOutput?,
    val provider: EsploraUtxo?,
)

data class BitcoinUtxoComparison(
    val mismatches: List<BitcoinUtxoMismatch>,
    /** Owned outputs spent by transactions absent from the reconstruction. */
    val missingParents: List<BitcoinOutpoint>,
) {
    val matches: Boolean get() = mismatches.isEmpty() && missingParents.isEmpty()
}

/**
 * Compares the reconstructed unspent set with a provider UTXO snapshot taken at the same chain state.
 * Pending spends are projected as spent on both sides; conflicting spends leave the output unspent.
 */
fun compareUtxoSets(
    graph: BitcoinUtxoGraph,
    provider: EsploraUtxoSnapshot,
): BitcoinUtxoComparison {
    val reconstructed = graph.unspent.associateBy { it.outpoint }
    val reported = provider.utxos.associateBy { it.outpoint }
    require(reported.size == provider.utxos.size) { "Duplicate UTXO across provider responses" }
    val mismatches = mutableListOf<BitcoinUtxoMismatch>()
    (reconstructed.keys + reported.keys).sorted().forEach { outpoint ->
        val ours = reconstructed[outpoint]
        val theirs = reported[outpoint]

        fun add(kind: BitcoinUtxoMismatchKind) = mismatches.add(BitcoinUtxoMismatch(kind, outpoint, ours, theirs))
        when {
            theirs != null && theirs.address !in graph.ownedAddresses -> add(BitcoinUtxoMismatchKind.UNWATCHED_ADDRESS)
            ours == null -> add(BitcoinUtxoMismatchKind.MISSING_FROM_RECONSTRUCTION)
            theirs == null -> add(BitcoinUtxoMismatchKind.MISSING_FROM_PROVIDER)
            else -> {
                if (ours.value != theirs.value) add(BitcoinUtxoMismatchKind.VALUE_DIFFERS)
                if (ours.address != theirs.address) add(BitcoinUtxoMismatchKind.ADDRESS_DIFFERS)
                if (ours.block != theirs.block) add(BitcoinUtxoMismatchKind.CONFIRMATION_DIFFERS)
            }
        }
    }
    return BitcoinUtxoComparison(mismatches, graph.missingParents)
}

/** Sums a provider snapshot per watched account, confirmed and including unconfirmed outputs. */
fun EsploraUtxoSnapshot.balances(ownedAddresses: Map<String, AccountId>): Map<AccountId, Pair<ExactInteger, ExactInteger>> {
    val owners = ownedAddresses.mapKeys { bitcoinMainnetAddress(it.key) }
    return owners.values.toSet().sortedBy { it.value }.associateWith { account ->
        val owned = utxos.filter { owners[it.address] == account }
        owned.filter { it.block != null }.fold(ExactInteger.ZERO) { sum, utxo -> sum + utxo.value } to
            owned.fold(ExactInteger.ZERO) { sum, utxo -> sum + utxo.value }
    }
}
