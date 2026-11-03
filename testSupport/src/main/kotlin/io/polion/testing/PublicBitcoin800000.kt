package io.polion.testing

/** Public block 800000 transaction, cross-checked against Blockstream and mempool.space. See docs/phase4-public-fixture.md. */
object PublicBitcoin800000 {
    const val TRANSACTION_ID =
        "d41f5de48325e79070ccd3a23005f7a3b405f3ce1faa4df09f6d71770497e9d5"
    const val BLOCK_HASH =
        "00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054"
    const val BLOCK_HEADER =
        "00601d3455bb9fbd966b3ea2dc42d0c22722e4c0c1729fad17210100000000000000000055087fab" +
            "0c8f3f89f8bcfd4df26c504d81b0a88e04907161838c0c53001af09135edbd64943805175e955e06"
    const val RAW_TRANSACTION =
        "020000000001016504bf2c6f9736a3af23a9693e81ef69a70e974f91c6ef3d2e38b7bedddb92a901" +
            "00000000ffffffff02e42f0200000000002251202d618c1f73d5133fdc97d545bfbf55b4cba2ab2a" +
            "9d41e4596b1df6b8ea9d93480b7404000000000016001464dbbc84f12f32699ca5010faa618d6a25" +
            "559b6f02483045022100f404e977e0a3dee1e9da7708db6ce6f3cbe80e6ffbbb6364bd2c725af200" +
            "520a02201faca96001ac7f82fcea71e03b29deeaac6525c3bb8abe3b3c64544af16b69850121025b" +
            "1b8e6cd2ebc837fc57928c688b9b4d192f9001d03d1831510a6e511ca3fa5e00000000"
    val transaction =
        """
{
  "txid": "d41f5de48325e79070ccd3a23005f7a3b405f3ce1faa4df09f6d71770497e9d5",
  "version": 2,
  "locktime": 0,
  "vin": [
    {
      "txid": "a992dbddbeb7382e3defc6914f970ea769ef813e69a923afa336976f2cbf0465",
      "vout": 1,
      "prevout": {
        "scriptpubkey": "001464dbbc84f12f32699ca5010faa618d6a25559b6f",
        "scriptpubkey_asm": "OP_0 OP_PUSHBYTES_20 64dbbc84f12f32699ca5010faa618d6a25559b6f",
        "scriptpubkey_type": "v0_p2wpkh",
        "scriptpubkey_address": "bc1qvndmep839uexn899qy865cvddgj4txm0nkjua9",
        "value": 604308
      },
      "scriptsig": "",
      "scriptsig_asm": "",
      "witness": [
        "3045022100f404e977e0a3dee1e9da7708db6ce6f3cbe80e6ffbbb6364bd2c725af200520a02201faca96001ac7f82fcea71e03b29deeaac6525c3bb8abe3b3c64544af16b698501",
        "025b1b8e6cd2ebc837fc57928c688b9b4d192f9001d03d1831510a6e511ca3fa5e"
      ],
      "is_coinbase": false,
      "sequence": 4294967295
    }
  ],
  "vout": [
    {
      "scriptpubkey": "51202d618c1f73d5133fdc97d545bfbf55b4cba2ab2a9d41e4596b1df6b8ea9d9348",
      "scriptpubkey_asm": "OP_PUSHNUM_1 OP_PUSHBYTES_32 2d618c1f73d5133fdc97d545bfbf55b4cba2ab2a9d41e4596b1df6b8ea9d9348",
      "scriptpubkey_type": "v1_p2tr",
      "scriptpubkey_address": "bc1p94scc8mn65fnlhyh64zml064kn9692e2n4q7gkttrhmt365ajdyq0m2mzh",
      "value": 143332
    },
    {
      "scriptpubkey": "001464dbbc84f12f32699ca5010faa618d6a25559b6f",
      "scriptpubkey_asm": "OP_0 OP_PUSHBYTES_20 64dbbc84f12f32699ca5010faa618d6a25559b6f",
      "scriptpubkey_type": "v0_p2wpkh",
      "scriptpubkey_address": "bc1qvndmep839uexn899qy865cvddgj4txm0nkjua9",
      "value": 291851
    }
  ],
  "size": 235,
  "weight": 610,
  "fee": 169125,
  "status": {
    "confirmed": true,
    "block_height": 800000,
    "block_hash": "00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054",
    "block_time": 1690168629
  }
}
        """.trimIndent()
}
