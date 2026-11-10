package io.polion.core.evm

import io.polion.core.model.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EvmIdentityTest {
    // Test vectors from EIP-55.
    private val vectors =
        listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )

    @Test fun eip55ChecksumsMatchTheSpecification() {
        vectors.forEach { checksummed ->
            assertEquals(checksummed.lowercase(), evmAddress(checksummed))
            assertEquals(checksummed, eip55(checksummed.lowercase()))
        }
        // A single wrong capital fails; all-lower and all-upper carry no checksum.
        assertFails { evmAddress("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD") }
        assertEquals("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", evmAddress("0x5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED"))
        listOf("", "0x123", "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", "0xzzaeb6053f3e94c9b9a09f33669435e7ef1beaed").forEach {
            assertFails(it) { evmAddress(it) }
        }
    }

    @Test fun quantitiesAndIdentities() {
        assertEquals("0", evmQuantity("0x0").toString())
        assertEquals("255", evmQuantity("0xff").toString())
        val max = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        assertEquals(max, evmQuantity("0x" + "f".repeat(64)).toString())
        assertEquals(max, evmQuantity(max).toString())
        assertEquals("7", evmQuantity("0007").toString())
        listOf("", "0x", "-1", "1.5", "0x" + "f".repeat(65), "0xg").forEach { assertFails(it) { evmQuantity(it) } }
        assertEquals(AssetId("eip155:8453/native"), EvmAssets.native(8453))
        assertEquals(
            AssetId("eip155:1/erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
            EvmAssets.token(1, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
        )
        assertEquals(8453L, EvmAssets.chainOf(EvmAssets.native(8453)))
        assertEquals(10L, evmChainId(evmNetwork(10)))
        assertEquals(null, evmChainId(EVM_FAMILY))
    }
}
