package io.valroot.core.bitcoin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainnetEndpointTest {
    @Test fun onlyTheMainnetGenesisBlockPasses() {
        requireMainnetEndpoint { path -> if (path == "/block-height/0") "$BITCOIN_GENESIS_HASH\n" else error(path) }
        val testnet = "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
        val failure = assertFailsWith<BitcoinProviderException> { requireMainnetEndpoint { testnet } }
        assertEquals(BitcoinProviderFailure.WRONG_NETWORK, failure.reason)
    }
}
