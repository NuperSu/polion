package io.polion.core.bitcoin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BitcoinSpecVectorsTest {
    @Test fun bip173ValidMainnetSegwitAddressesAreAccepted() {
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", bitcoinMainnetAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            bitcoinMainnetAddress("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4").lowercase(),
        )
    }

    @Test fun bip173InvalidSegwitAddressesAreRejected() {
        val rejected =
            listOf(
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",
                "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2",
                "bc1rw5uspcuh",
                "bc10w508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kw5rljs90",
                "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P",
                "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7",
                "bc1zw508d6qejxtdg4y5r3zarvaryvqyzf3du",
                "bc1gmk9yu",
            )
        rejected.forEach { address -> assertFails("accepted invalid BIP 173 address $address") { bitcoinMainnetAddress(address) } }
    }

    @Test fun bip380ChecksumVectorsAreCheckedExactly() {
        assertEquals("89f8spxm", descriptorChecksum("raw(deadbeef)"))
        assertFails { bitcoinWallet("raw(deadbeef)#89f8spxmx") }
        assertFails { bitcoinWallet("raw(deadbeef)#89f8spx") }
        assertFails { bitcoinWallet("raw(deedbeef)#89f8spxm") }
        assertFails { bitcoinWallet("raw(deadbeef)#") }
    }
}
