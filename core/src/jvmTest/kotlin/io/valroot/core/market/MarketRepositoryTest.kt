package io.valroot.core.market

import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.NetworkId
import io.valroot.core.model.Price
import io.valroot.core.model.ProviderId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MarketRepositoryTest {
    @Test fun indexedLookupMatchesSelectionAcrossPoliciesAndWithdrawalTimes() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = MarketRepository(db)
            register(repository)
            val observations =
                (0 until 36).map { i ->
                    val provider =
                        when (i % 3) {
                            0 -> null
                            1 -> provider1
                            else -> provider2
                        }
                    PriceObservation(
                        "price-$i",
                        Price(token1, usd, ExactDecimal.parse("${i + 1}")),
                        provider,
                        (i % 8 * 10).toLong(),
                        (
                            i % 6 *
                                12
                        ).toLong(),
                        provider == null,
                    )
                }
            observations.forEach(repository::recordPrice)
            val withdrawn = observations.first { it.manual }
            repository.withdrawManualPrice(withdrawn.id, 40, 50)
            for (at in listOf(0L, 25L, 50L, 80L, 120L)) {
                for (known in listOf(0L, 30L, 49L, 50L, 120L)) {
                    for (providers in listOf(emptyList(), listOf(provider1), listOf(provider1, provider2), listOf(provider2, provider1))) {
                        for (fresh in listOf(false, true)) {
                            for (latest in listOf(false, true)) {
                                val policy = PricePolicy(providers, 20, fresh, latest)

                                fun stale(o: PriceObservation) = o.validAtMillis < at - policy.maxAgeMillis
                                val expected =
                                    observations
                                        .filter {
                                            it.validAtMillis <= at &&
                                                it.observedAtMillis <= known &&
                                                (it.manual || it.providerId in providers) &&
                                                !(it == withdrawn && at >= 40 && known >= 50)
                                        }.minWithOrNull(
                                            compareBy<PriceObservation> { if (fresh && stale(it)) 1 else 0 }
                                                .thenByDescending { if (latest) it.validAtMillis else Long.MIN_VALUE }
                                                .thenBy { if (it.manual) 0 else 1 }
                                                .thenBy { if (it.manual) -1 else providers.indexOf(it.providerId) }
                                                .thenByDescending { it.validAtMillis }
                                                .thenByDescending { it.observedAtMillis }
                                                .thenBy { it.id },
                                        )
                                val status =
                                    if (expected ==
                                        null
                                    ) {
                                        PriceStatus.MISSING
                                    } else if (stale(expected)) {
                                        PriceStatus.STALE
                                    } else {
                                        PriceStatus.FRESH
                                    }
                                assertEquals(PriceResult(status, expected), repository.lookup(token1, usd, at, known, policy))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun switchingToProviderPricesEndsAllActiveOverridesWithoutChangingHistoryOrOtherPairs() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = MarketRepository(db)
            register(repository)
            val policy = PricePolicy(listOf(provider1), 1000)
            val provider = PriceObservation("provider", Price(token1, usd, ExactDecimal.parse("5")), provider1, 10, 10)
            val first = PriceObservation("manual-first", Price(token1, usd, ExactDecimal.parse("7")), null, 20, 20, true)
            val second = PriceObservation("manual-second", Price(token1, usd, ExactDecimal.parse("9")), null, 30, 30, true)
            val other = PriceObservation("manual-other", Price(token2, usd, ExactDecimal.parse("3")), null, 20, 20, true)
            listOf(provider, first, second, other).forEach { repository.recordPrice(it) }
            assertEquals(second, repository.lookup(token1, usd, 40, 40, policy).observation)
            assertEquals(2, repository.withdrawActiveManualPrices(token1, usd, 50))
            assertEquals(0, repository.withdrawActiveManualPrices(token1, usd, 50))
            assertEquals(second, repository.lookup(token1, usd, 49, 50, policy).observation)
            assertEquals(provider, repository.lookup(token1, usd, 50, 50, policy).observation)
            assertEquals(other, repository.lookup(token2, usd, 50, 50, policy).observation)
        }
    }

    @Test fun newerAutomaticPriceReplacesOlderManualOverrideButNewerManualStillWins() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = MarketRepository(db)
            register(repository)
            val policy = PricePolicy(listOf(provider1), 1000, preferLatest = true)
            val manual = PriceObservation("old-manual", Price(token1, usd, ExactDecimal.parse("80689")), null, 10, 10, true)
            val automatic = PriceObservation("automatic", Price(token1, usd, ExactDecimal.parse("83700")), provider1, 20, 20)
            repository.recordPrice(manual)
            repository.recordPrice(automatic)
            assertEquals(automatic, repository.lookup(token1, usd, 30, 30, policy).observation)
            val newerManual = PriceObservation("new-manual", Price(token1, usd, ExactDecimal.parse("84000")), null, 40, 40, true)
            repository.recordPrice(newerManual)
            assertEquals(newerManual, repository.lookup(token1, usd, 50, 50, policy).observation)
        }
    }

    private val usd = AssetId("fiat:usd")
    private val token1 = AssetId("evm:one")
    private val token2 = AssetId("evm:two")
    private val provider1 = ProviderId("high")
    private val provider2 = ProviderId("low")

    private fun register(repository: MarketRepository) {
        repository.registerAsset(AssetDefinition(usd, AssetIdentity(AssetKind.FIAT, null, "USD")), AssetMetadata("USD", "US dollar", 2, 0))
        repository.registerAsset(
            AssetDefinition(token1, AssetIdentity(AssetKind.TOKEN, NetworkId("chain:1"), "0x01")),
            AssetMetadata("SAME", "Token one", 18, 0),
        )
        repository.registerAsset(
            AssetDefinition(token2, AssetIdentity(AssetKind.TOKEN, NetworkId("chain:1"), "0x02")),
            AssetMetadata("SAME", "Token two", 24, 0),
        )
        repository.registerAsset(
            AssetDefinition(AssetId("btc"), AssetIdentity(AssetKind.NATIVE, NetworkId("bitcoin:main"), "native")),
            AssetMetadata("BTC", "Bitcoin", 8, 0),
        )
        repository.registerAsset(
            AssetDefinition(AssetId("nft"), AssetIdentity(AssetKind.NFT, NetworkId("chain:1"), "0xnft", "42")),
            AssetMetadata("NFT", "NFT", 0, 0),
        )
        repository.registerAsset(
            AssetDefinition(AssetId("custom"), AssetIdentity(AssetKind.CUSTOM, null, "custom:unique")),
            AssetMetadata("X", "Custom", 0, 0),
        )
    }

    @Test
    fun identityMetadataAndMappingsSurviveReopen() {
        val file = Files.createTempFile("valroot-market", ".sqlite")
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repository = MarketRepository(db)
                register(repository)
                assertEquals("SAME", repository.metadataAt(token1, 0)!!.symbol)
                assertEquals("SAME", repository.metadataAt(token2, 0)!!.symbol)
                repository.putMetadata(token1, AssetMetadata("NEW", "Token one", 18, 100))
                assertEquals("SAME", repository.metadataAt(token1, 99)!!.symbol)
                assertEquals("NEW", repository.metadataAt(token1, 100)!!.symbol)
                assertNull(repository.metadataAt(token1, -1))
                repository.mapProvider(provider1, "asset-one", token1)
                repository.mapProvider(provider1, "asset-two", token2)
                assertFailsWith<IllegalArgumentException> { repository.mapProvider(provider1, "asset-one", token2) }
                assertFailsWith<IllegalArgumentException> {
                    repository.registerAsset(
                        AssetDefinition(AssetId("other"), AssetIdentity(AssetKind.TOKEN, NetworkId("chain:1"), "0x01")),
                        AssetMetadata("SAME", "Duplicated", 18, 0),
                    )
                }
                assertFailsWith<IllegalArgumentException> { repository.putMetadata(token1, AssetMetadata("oops", "wrong", 9, 100)) }
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repository = MarketRepository(db)
                assertEquals(token1, repository.resolveProvider(provider1, "asset-one"))
                assertEquals(token2, repository.resolveProvider(provider1, "asset-two"))
                assertEquals("0x01", repository.asset(token1)!!.identity.reference)
                assertEquals("0x02", repository.asset(token2)!!.identity.reference)
                assertEquals("42", repository.asset(AssetId("nft"))!!.identity.tokenId)
                assertEquals(24, repository.metadataAt(token2, 100)!!.decimals)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun priceSelectionIsPreciseHistoricalAndExplicitAboutMissingOrStale() {
        val file = Files.createTempFile("valroot-prices", ".sqlite")
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repository = MarketRepository(db)
                register(repository)
                val policy = PricePolicy(listOf(provider1, provider2), 30)
                val missing = repository.lookup(token1, usd, 100, 100, policy)
                assertEquals(PriceStatus.MISSING, missing.status)
                assertNull(missing.value(AtomicAmount(token1, ExactInteger.parse("1000000000000000000")), 18))
                val low = PriceObservation("low", Price(token1, usd, ExactDecimal.parse("3.125")), provider2, 90, 90)
                val high = PriceObservation("high", Price(token1, usd, ExactDecimal.parse("4.123456789012345678901234")), provider1, 80, 95)
                repository.recordPrice(low)
                repository.recordPrice(high)
                repository.recordPrice(high)
                assertEquals(low, repository.lookup(token1, usd, 100, 94, policy).observation)
                val selected = repository.lookup(token1, usd, 100, 100, policy)
                assertEquals(high, selected.observation)
                assertEquals(low, repository.lookup(token1, usd, 100, 100, policy.copy(preferLatest = true)).observation)
                assertEquals(PriceStatus.FRESH, selected.status)
                assertEquals(
                    "8.246913578024691357802468000000000000000000",
                    selected.value(AtomicAmount(token1, ExactInteger.parse("2000000000000000000")), 18).toString(),
                )
                assertEquals(low, repository.lookup(token1, usd, 111, 111, policy).observation)
                assertEquals(PriceStatus.FRESH, repository.lookup(token1, usd, 111, 111, policy).status)
                assertEquals(high, repository.lookup(token1, usd, 111, 111, policy.copy(preferFresh = false)).observation)
                assertEquals(PriceStatus.STALE, repository.lookup(token1, usd, 121, 121, policy).status)
                assertEquals(PriceStatus.MISSING, repository.lookup(token2, usd, 100, 100, policy).status)
                val manual = PriceObservation("manual", Price(token1, usd, ExactDecimal.parse("9")), null, 96, 101, true)
                repository.recordPrice(manual)
                assertEquals(high, repository.lookup(token1, usd, 100, 100, policy).observation)
                assertEquals(manual, repository.lookup(token1, usd, 100, 101, policy).observation)
                assertEquals(manual, repository.lookup(token1, usd, 100, 101, policy.copy(preferLatest = true)).observation)
                repository.withdrawManualPrice("manual", 125, 130)
                repository.withdrawManualPrice("manual", 125, 130)
                assertEquals(manual, repository.lookup(token1, usd, 127, 129, policy).observation)
                assertEquals(manual, repository.lookup(token1, usd, 124, 130, policy).observation)
                assertEquals(high, repository.lookup(token1, usd, 125, 130, policy).observation)
                assertEquals(PriceStatus.STALE, repository.lookup(token1, usd, 127, 127, policy).status)
                assertFailsWith<IllegalArgumentException> { repository.withdrawManualPrice("low", 130, 130) }
                assertFailsWith<IllegalArgumentException> { repository.withdrawManualPrice("manual", 95, 130) }
                assertFailsWith<IllegalArgumentException> {
                    repository.recordPrice(
                        high.copy(price = Price(token1, usd, ExactDecimal.parse("5"))),
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    repository.recordPrice(low.copy(id = "missing-asset", price = Price(AssetId("unknown"), usd, ExactDecimal.parse("1"))))
                }
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                assertEquals(
                    "9",
                    MarketRepository(
                        db,
                    ).lookup(token1, usd, 100, 101, PricePolicy(listOf(provider1), 30)).observation!!.price.value.toString(),
                )
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
