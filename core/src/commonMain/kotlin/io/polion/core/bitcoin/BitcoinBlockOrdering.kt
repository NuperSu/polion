package io.polion.core.bitcoin

import io.polion.core.storage.RawObservation
import io.polion.core.storage.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Resolves a confirmed transaction's position in the indexer's block transaction list. */
object BitcoinBlockOrdering {
    data class Resolution(
        val transactions: List<RawObservation>,
        val blocks: List<RawObservation>,
    )

    fun resolve(
        observations: List<RawObservation>,
        transport: EsploraTransport,
    ): Resolution {
        val blocks = mutableMapOf<String, Pair<Long, RawObservation>>()
        val indices = mutableMapOf<String, Map<String, Int>>()
        val transactions =
            observations.map { observation ->
                val tx = Json.parseToJsonElement(observation.payload).jsonObject
                val status = tx.getValue("status").jsonObject
                if (!status.getValue("confirmed").jsonPrimitive.boolean) {
                    observation
                } else {
                    val hash = status.getValue("block_hash").jsonPrimitive.content
                    val height = status.getValue("block_height").jsonPrimitive.long
                    require(hash.matches(Regex("[0-9a-f]{64}")) && height >= 0)
                    if (hash !in blocks) {
                        val payload = transport.get("/block/$hash/txids")
                        val txids = Json.parseToJsonElement(payload).jsonArray.map { it.jsonPrimitive.content }
                        require(
                            txids.isNotEmpty() && txids.all { it.matches(Regex("[0-9a-f]{64}")) } && txids.distinct().size == txids.size,
                        )
                        indices[hash] = txids.withIndex().associate { it.value to it.index }
                        blocks[hash] = height to
                            RawObservation(
                                "bitcoin-block:$hash",
                                "bitcoin-block",
                                hash,
                                BITCOIN_MAINNET.value,
                                observation.sourceId,
                                observation.retrievedAt,
                                payload,
                                "esplora-txids-1",
                                sha256(payload),
                                "esplora-block-txids",
                            )
                    }
                    require(blocks.getValue(hash).first == height) { "Conflicting block height" }
                    val index = requireNotNull(indices.getValue(hash)[observation.externalId]) { "Transaction absent from claimed block" }
                    observation.copy(sourceId = observation.sourceId + ":block-order", context = "bitcoin-order:$height:$index:$hash")
                }
            }
        return Resolution(transactions, blocks.values.map { it.second })
    }
}
