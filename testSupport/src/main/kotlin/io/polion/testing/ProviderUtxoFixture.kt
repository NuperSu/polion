package io.polion.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Serves the Esplora `/address/{address}/utxo` response that the provider would report for the given transactions:
 * each output paying [address] that no supplied transaction spends. Test fixtures use this so the UTXO comparison
 * receives the same chain state the scan observed.
 */
fun utxoResponse(
    address: String,
    payloads: Collection<String>,
): String {
    val spent = mutableSetOf<Pair<String, Long>>()
    payloads.forEach { payload ->
        Json
            .parseToJsonElement(payload)
            .jsonObject
            .getValue("vin")
            .jsonArray
            .forEach { input ->
                val item = input.jsonObject
                val txid = item["txid"]?.jsonPrimitive?.content
                val vout = item["vout"]?.jsonPrimitive?.content?.toLong()
                if (txid != null && vout != null) {
                    spent.add(txid to vout)
                }
            }
    }
    val entries =
        payloads.flatMap { payload ->
            val tx = Json.parseToJsonElement(payload).jsonObject
            val txid = tx.getValue("txid").jsonPrimitive.content
            val status = tx.getValue("status").jsonObject
            tx
                .getValue("vout")
                .jsonArray
                .mapIndexedNotNull { index, output ->
                    val item = output.jsonObject
                    if (item["scriptpubkey_address"]?.jsonPrimitive?.content != address || (txid to index.toLong()) in spent) {
                        null
                    } else {
                        val confirmed = status.getValue("confirmed").jsonPrimitive.content == "true"
                        val block =
                            if (confirmed) {
                                ""","status":{"confirmed":true,"block_height":${status.getValue(
                                    "block_height",
                                ).jsonPrimitive.content},"block_hash":"${status.getValue("block_hash").jsonPrimitive.content}"}"""
                            } else {
                                ""","status":{"confirmed":false}"""
                            }
                        """{"txid":"$txid","vout":$index,"value":${item.getValue("value").jsonPrimitive.content}$block}"""
                    }
                }
        }
    return entries.joinToString(",", "[", "]")
}
