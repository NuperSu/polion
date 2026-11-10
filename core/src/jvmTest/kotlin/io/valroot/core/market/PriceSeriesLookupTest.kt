package io.valroot.core.market

import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.Price
import io.valroot.core.model.ProviderId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceSeriesLookupTest {
    private val btc = AssetId("btc")
    private val usd = AssetId("usd")
    private val kraken = ProviderId("kraken")
    private val gecko = ProviderId("coingecko")

    @Test fun seriesLookupMatchesPerInstantLookupExactly() {
        repeat(25) { seed ->
            JvmSqliteDatabase(":memory:").use { db ->
                val market = MarketRepository(db)
                market.registerAsset(
                    AssetDefinition(btc, AssetIdentity(AssetKind.CUSTOM, null, "btc")),
                    AssetMetadata("BTC", "Bitcoin", 8, 0),
                )
                market.registerAsset(AssetDefinition(usd, AssetIdentity(AssetKind.FIAT, null, "usd")), AssetMetadata("USD", "Dollar", 2, 0))
                val random = Random(seed)
                val hour = 3_600_000L
                val base = 1_000_000_000_000L
                // Dense recent data, a few old points far outside any window, ties, and manual overrides.
                val validTimes =
                    (0 until 400).map { base + random.nextLong(-20L * 24 * hour, 3 * 24 * hour) } + List(5) { base - 400L * 24 * hour }
                validTimes.forEachIndexed { i, valid ->
                    val manual = random.nextInt(10) == 0
                    val provider =
                        if (manual) {
                            null
                        } else if (random.nextBoolean()) {
                            kraken
                        } else {
                            gecko
                        }
                    market.recordPrice(
                        PriceObservation(
                            "p$i",
                            Price(btc, usd, ExactDecimal.parse("${random.nextInt(1, 100_000)}.${random.nextInt(10, 99)}")),
                            provider,
                            valid,
                            valid + random.nextLong(0, 2 * hour),
                            manual,
                        ),
                    )
                }
                val manualIds = db.query("SELECT id FROM market_prices WHERE manual=1").map { it.single()!! }
                manualIds.shuffled(random).take(manualIds.size / 2).forEach { id ->
                    val at = base + random.nextLong(-10L * 24 * hour, 2 * 24 * hour)
                    db.execute("INSERT INTO market_override_withdrawals VALUES (?, ?, ?)", listOf(id, at.toString(), at.toString()))
                }
                val knownAt = base + random.nextLong(0, 3 * 24 * hour)
                val start = base - random.nextLong(0, 30L * 24 * hour)
                val times = (0..120).map { start + it * (knownAt - start) / 120 } + listOf(base - 500L * 24 * hour)

                fun policy(at: Long) =
                    PricePolicy(
                        listOf(kraken, gecko),
                        if (at >=
                            knownAt - 24 * hour
                        ) {
                            7_200_000L
                        } else {
                            86_400_000L
                        },
                        preferLatest = true,
                    )
                val expected = times.map { market.lookup(btc, usd, it, knownAt, policy(it)) }
                assertEquals(
                    expected,
                    market.lookupSeries(btc, usd, times, knownAt, ::policy, rowsPerInstant = Long.MAX_VALUE),
                    "window $seed",
                )
                assertEquals(expected, market.lookupSeries(btc, usd, times, knownAt, ::policy, rowsPerInstant = 0), "per instant $seed")
            }
        }
    }
}
