package io.polion.core.market

import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.NetworkId
import io.polion.core.model.Price
import io.polion.core.model.ProviderId
import io.polion.core.storage.SqliteDatabase

/** Durable asset identities, time-versioned metadata and immutable price observations. */
class MarketRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute("PRAGMA foreign_keys = ON")
        db.transaction {
            db.execute("CREATE TABLE IF NOT EXISTS market_schema (version INTEGER NOT NULL)")
            val rows = db.query("SELECT version FROM market_schema")
            if (rows.isEmpty()) {
                db.execute("INSERT INTO market_schema VALUES (2)")
                db.execute(
                    "CREATE TABLE market_assets (id TEXT PRIMARY KEY, kind TEXT NOT NULL, network TEXT NOT NULL, reference TEXT NOT NULL, token_id TEXT NOT NULL, UNIQUE(kind, network, reference, token_id))",
                )
                db.execute(
                    "CREATE TABLE market_metadata (asset_id TEXT NOT NULL REFERENCES market_assets(id), effective_at INTEGER NOT NULL, symbol TEXT NOT NULL, name TEXT NOT NULL, decimals INTEGER NOT NULL, PRIMARY KEY(asset_id, effective_at))",
                )
                db.execute(
                    "CREATE TABLE market_provider_mappings (provider TEXT NOT NULL, external_id TEXT NOT NULL, asset_id TEXT NOT NULL REFERENCES market_assets(id), PRIMARY KEY(provider, external_id))",
                )
                db.execute(
                    "CREATE TABLE market_prices (id TEXT PRIMARY KEY, base_asset TEXT NOT NULL REFERENCES market_assets(id), quote_asset TEXT NOT NULL REFERENCES market_assets(id), value TEXT NOT NULL, provider TEXT, valid_at INTEGER NOT NULL, observed_at INTEGER NOT NULL, manual INTEGER NOT NULL)",
                )
                db.execute("CREATE INDEX market_prices_pair_time ON market_prices(base_asset, quote_asset, valid_at, observed_at)")
                db.execute(
                    "CREATE TABLE market_override_withdrawals (price_id TEXT NOT NULL REFERENCES market_prices(id), effective_at INTEGER NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(price_id, effective_at, observed_at))",
                )
            } else {
                require(rows.size == 1 && rows.single().single() in setOf("1", "2")) { "Unsupported market schema" }
                if (rows.single().single() == "1") {
                    db.execute(
                        "CREATE TABLE market_override_withdrawals (price_id TEXT NOT NULL REFERENCES market_prices(id), effective_at INTEGER NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(price_id, effective_at, observed_at))",
                    )
                    db.execute("UPDATE market_schema SET version=2")
                }
                val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it.single() }.toSet()
                check(
                    tables.containsAll(
                        setOf(
                            "market_assets",
                            "market_metadata",
                            "market_provider_mappings",
                            "market_prices",
                            "market_override_withdrawals",
                        ),
                    ),
                ) {
                    "Market schema is incomplete"
                }
            }
            // Each lookup asks for one source (manual or a provider); this index finds its newest row directly.
            db.execute(
                "CREATE INDEX IF NOT EXISTS market_prices_source_time ON market_prices(base_asset, quote_asset, manual, provider, valid_at, observed_at)",
            )
            db.execute("CREATE INDEX IF NOT EXISTS market_withdrawals_price ON market_override_withdrawals(price_id, effective_at)")
        }
    }

    fun registerAsset(
        definition: AssetDefinition,
        metadata: AssetMetadata,
    ) = db.transaction {
        val identity = definition.identity
        val values =
            listOf(
                definition.id.value,
                identity.kind.name,
                identity.network?.value ?: "",
                identity.reference,
                identity.tokenId ?: "",
            )
        val existing =
            db
                .query(
                    "SELECT id, kind, network, reference, token_id FROM market_assets WHERE id=?",
                    listOf(definition.id.value),
                ).singleOrNull()
        require(existing == null || existing == values) { "Asset identity cannot be changed" }
        val owner =
            db
                .query(
                    "SELECT id FROM market_assets WHERE kind=? AND network=? AND reference=? AND token_id=?",
                    values.drop(1),
                ).singleOrNull()
                ?.single()
        require(owner == null || owner == definition.id.value) { "Asset identity already registered" }
        db.execute("INSERT OR IGNORE INTO market_assets VALUES (?, ?, ?, ?, ?)", values)
        putMetadataInTransaction(definition.id, metadata)
    }

    fun putMetadata(
        asset: AssetId,
        metadata: AssetMetadata,
    ) = db.transaction { putMetadataInTransaction(asset, metadata) }

    private fun putMetadataInTransaction(
        asset: AssetId,
        metadata: AssetMetadata,
    ) {
        requireAsset(asset)
        val values =
            listOf(asset.value, metadata.effectiveAtMillis.toString(), metadata.symbol, metadata.name, metadata.decimals.toString())
        val existing =
            db
                .query(
                    "SELECT asset_id, effective_at, symbol, name, decimals FROM market_metadata WHERE asset_id=? AND effective_at=?",
                    values.take(2),
                ).singleOrNull()
        require(existing == null || existing == values) { "Conflicting metadata version" }
        db.execute("INSERT OR IGNORE INTO market_metadata VALUES (?, ?, ?, ?, ?)", values)
    }

    /** Every registered asset ID, in ID order. */
    fun assetIds(): List<AssetId> = db.query("SELECT id FROM market_assets ORDER BY id").map { AssetId(it.single()!!) }

    fun asset(id: AssetId): AssetDefinition? =
        db.query("SELECT kind, network, reference, token_id FROM market_assets WHERE id=?", listOf(id.value)).singleOrNull()?.let {
            AssetDefinition(
                id,
                AssetIdentity(
                    AssetKind.valueOf(it[0]!!),
                    it[1]!!.takeIf(String::isNotEmpty)?.let(::NetworkId),
                    it[2]!!,
                    it[3]!!.takeIf(String::isNotEmpty),
                ),
            )
        }

    /** Candidate non-fiat bases with saved quotes visible at the requested market/knowledge time. */
    fun savedPricedAssets(
        quote: AssetId,
        atMillis: Long,
        knownAtMillis: Long,
    ): List<AssetId> =
        db
            .query(
                "SELECT DISTINCT p.base_asset FROM market_prices p JOIN market_assets a ON a.id=p.base_asset " +
                    "WHERE p.quote_asset=? AND p.valid_at<=? AND p.observed_at<=? AND a.kind<>? ORDER BY p.base_asset",
                listOf(quote.value, atMillis.toString(), knownAtMillis.toString(), AssetKind.FIAT.name),
            ).map { AssetId(requireNotNull(it.single())) }

    fun metadataAt(
        asset: AssetId,
        atMillis: Long,
    ): AssetMetadata? =
        db
            .query(
                "SELECT symbol, name, decimals, effective_at FROM market_metadata WHERE asset_id=? AND effective_at<=CAST(? AS INTEGER) ORDER BY effective_at DESC LIMIT 1",
                listOf(asset.value, atMillis.toString()),
            ).singleOrNull()
            ?.let { AssetMetadata(it[0]!!, it[1]!!, it[2]!!.toInt(), it[3]!!.toLong()) }

    /** Every metadata version of [asset], newest first, for resolving many instants without one query each. */
    fun metadataHistory(asset: AssetId): List<AssetMetadata> =
        db
            .query(
                "SELECT symbol, name, decimals, effective_at FROM market_metadata WHERE asset_id=? ORDER BY effective_at DESC",
                listOf(asset.value),
            ).map { AssetMetadata(it[0]!!, it[1]!!, it[2]!!.toInt(), it[3]!!.toLong()) }

    fun mapProvider(
        provider: ProviderId,
        externalId: String,
        asset: AssetId,
    ) = db.transaction {
        require(externalId.isNotBlank())
        requireAsset(asset)
        val existing =
            db
                .query(
                    "SELECT asset_id FROM market_provider_mappings WHERE provider=? AND external_id=?",
                    listOf(provider.value, externalId),
                ).singleOrNull()
                ?.single()
        require(existing == null || existing == asset.value) { "Provider identifier already maps to another asset" }
        db.execute("INSERT OR IGNORE INTO market_provider_mappings VALUES (?, ?, ?)", listOf(provider.value, externalId, asset.value))
    }

    fun resolveProvider(
        provider: ProviderId,
        externalId: String,
    ): AssetId? =
        db
            .query(
                "SELECT asset_id FROM market_provider_mappings WHERE provider=? AND external_id=?",
                listOf(provider.value, externalId),
            ).singleOrNull()
            ?.single()
            ?.let(::AssetId)

    fun recordPrice(
        observation: PriceObservation,
        authorize: () -> Unit = {},
    ) = recordPrices(listOf(observation), authorize)

    fun recordPrices(
        observations: List<PriceObservation>,
        authorize: () -> Unit = {},
    ) = db.transaction {
        authorize()
        observations.forEach { observation ->
            authorize()
            recordPriceInTransaction(observation)
        }
        authorize()
    }

    /** Avoid repeated unchanged provider points while preserving later revisions and reversions. */
    fun recordChangedProviderPrices(
        observations: List<PriceObservation>,
        authorize: () -> Unit = {},
    ) = db.transaction {
        authorize()
        observations.forEach { observation ->
            authorize()
            val provider = requireNotNull(observation.providerId) { "Provider observation required" }
            val latest =
                db
                    .query(
                        "SELECT value FROM market_prices WHERE base_asset=? AND quote_asset=? AND provider=? AND valid_at=? " +
                            "ORDER BY observed_at DESC, id ASC LIMIT 1",
                        listOf(
                            observation.price.baseAssetId.value,
                            observation.price.quoteAssetId.value,
                            provider.value,
                            observation.validAtMillis.toString(),
                        ),
                    ).singleOrNull()
                    ?.single()
            if (latest != observation.price.value.toString()) recordPriceInTransaction(observation)
        }
        authorize()
    }

    private fun recordPriceInTransaction(observation: PriceObservation) {
        requireAsset(observation.price.baseAssetId)
        requireAsset(observation.price.quoteAssetId)
        val values =
            listOf(
                observation.id,
                observation.price.baseAssetId.value,
                observation.price.quoteAssetId.value,
                observation.price.value.toString(),
                observation.providerId?.value,
                observation.validAtMillis.toString(),
                observation.observedAtMillis.toString(),
                if (observation.manual) "1" else "0",
            )
        val existing =
            db
                .query(
                    "SELECT id, base_asset, quote_asset, value, provider, valid_at, observed_at, manual FROM market_prices WHERE id=?",
                    listOf(observation.id),
                ).singleOrNull()
        require(existing == null || existing == values) { "Price observation ID already exists with different data" }
        db.execute("INSERT OR IGNORE INTO market_prices VALUES (?, ?, ?, ?, ?, ?, ?, ?)", values)
    }

    /** Withdraw an override from its effective time without rewriting earlier history. */
    fun withdrawManualPrice(
        id: String,
        effectiveAtMillis: Long,
        observedAtMillis: Long,
    ) = db.transaction {
        val row = db.query("SELECT manual, valid_at FROM market_prices WHERE id=?", listOf(id)).singleOrNull()
        require(row != null && row[0] == "1") { "Unknown manual price observation" }
        require(effectiveAtMillis >= row[1]!!.toLong()) { "Withdrawal precedes override" }
        db.execute(
            "INSERT OR IGNORE INTO market_override_withdrawals VALUES (?, ?, ?)",
            listOf(id, effectiveAtMillis.toString(), observedAtMillis.toString()),
        )
    }

    /** End every currently active manual override for a pair without changing prior valuations. */
    fun withdrawActiveManualPrices(
        base: AssetId,
        quote: AssetId,
        atMillis: Long,
    ): Int =
        db.transaction {
            val at = atMillis.toString()
            val ids =
                db
                    .query(
                        "SELECT p.id FROM market_prices p WHERE p.base_asset=? AND p.quote_asset=? AND p.manual=1 " +
                            "AND p.valid_at<=CAST(? AS INTEGER) AND p.observed_at<=CAST(? AS INTEGER) " +
                            "AND NOT EXISTS (SELECT 1 FROM market_override_withdrawals w WHERE w.price_id=p.id " +
                            "AND w.effective_at<=CAST(? AS INTEGER) AND w.observed_at<=CAST(? AS INTEGER))",
                        listOf(base.value, quote.value, at, at, at, at),
                    ).map { requireNotNull(it.single()) }
            ids.forEach { id ->
                db.execute("INSERT OR IGNORE INTO market_override_withdrawals VALUES (?, ?, ?)", listOf(id, at, at))
            }
            ids.size
        }

    /**
     * The most recent valid observation wins, whether manual or provider. A manual observation wins only a tie, so newer
     * automatic data replaces an older manual override while a newer manual entry still takes precedence.
     */
    fun lookup(
        base: AssetId,
        quote: AssetId,
        atMillis: Long,
        knownAtMillis: Long,
        policy: PricePolicy,
    ): PriceResult {
        require(base != quote)

        val observations =
            listOfNotNull(latest(base, quote, atMillis, knownAtMillis, null)) +
                policy.providerPriority.mapNotNull { latest(base, quote, atMillis, knownAtMillis, it) }
        return select(observations, atMillis, policy)
    }

    /** The newest manual ([provider] null) or provider observation valid and known at the instant, if not withdrawn. */
    private fun latest(
        base: AssetId,
        quote: AssetId,
        atMillis: Long,
        knownAtMillis: Long,
        provider: ProviderId?,
    ): PriceObservation? {
        val filter = if (provider == null) "p.manual=1" else "p.manual=0 AND p.provider=?"
        val args =
            listOf(
                base.value,
                quote.value,
                atMillis.toString(),
                knownAtMillis.toString(),
                atMillis.toString(),
                knownAtMillis.toString(),
            ) +
                listOfNotNull(provider?.value)
        val row =
            db
                .query(
                    "SELECT p.id, p.value, p.provider, p.valid_at, p.observed_at, p.manual FROM market_prices p " +
                        "WHERE p.base_asset=? AND p.quote_asset=? AND p.valid_at<=CAST(? AS INTEGER) " +
                        "AND p.observed_at<=CAST(? AS INTEGER) " +
                        "AND NOT EXISTS (SELECT 1 FROM market_override_withdrawals w WHERE w.price_id=p.id " +
                        "AND w.effective_at<=CAST(? AS INTEGER) AND w.observed_at<=CAST(? AS INTEGER)) AND $filter " +
                        "ORDER BY p.valid_at DESC, p.observed_at DESC, p.id ASC LIMIT 1",
                    args,
                ).singleOrNull() ?: return null
        return PriceObservation(
            row[0]!!,
            Price(base, quote, ExactDecimal.parse(row[1]!!)),
            row[2]?.let(::ProviderId),
            row[3]!!.toLong(),
            row[4]!!.toLong(),
            row[5] == "1",
        )
    }

    /** Chooses among the latest manual and per-provider observations exactly as [lookup] documents. */
    private fun select(
        observations: List<PriceObservation>,
        atMillis: Long,
        policy: PricePolicy,
    ): PriceResult {
        fun isStale(observation: PriceObservation): Boolean =
            atMillis >= Long.MIN_VALUE + policy.maxAgeMillis && observation.validAtMillis < atMillis - policy.maxAgeMillis
        val selected =
            observations.minWithOrNull(
                compareBy<PriceObservation> { if (policy.preferFresh && isStale(it)) 1 else 0 }
                    .thenByDescending { if (policy.preferLatest) it.validAtMillis else Long.MIN_VALUE }
                    .thenBy { if (it.manual) 0 else 1 }
                    .thenBy { if (it.manual) -1 else policy.providerPriority.indexOf(it.providerId) }
                    .thenByDescending { it.validAtMillis }
                    .thenByDescending { it.observedAtMillis }
                    .thenBy { it.id },
            ) ?: return PriceResult(PriceStatus.MISSING, null)
        val stale = isStale(selected)
        return PriceResult(if (stale) PriceStatus.STALE else PriceStatus.FRESH, selected)
    }

    /**
     * [lookup] for many instants at once, with identical results. Dense recent data is read once and resolved in memory;
     * when the range holds many more rows than instants, SQLite resolves each instant instead. Measured on a phone, each
     * strategy is several times faster than the other in its own case.
     */
    fun lookupSeries(
        base: AssetId,
        quote: AssetId,
        times: List<Long>,
        knownAtMillis: Long,
        policyAt: (Long) -> PricePolicy,
        windowMillis: Long = 2 * 86_400_000L,
        // Above this many rows per instant and source, SQLite resolves each instant; tests set it to force either path.
        rowsPerInstant: Long = 10,
    ): List<PriceResult> {
        require(base != quote)
        if (times.isEmpty()) return emptyList()
        val policies = times.map(policyAt)
        val sources = listOf<ProviderId?>(null) + policies.flatMap { it.providerPriority }.distinct()
        val low = times.min().let { if (it >= Long.MIN_VALUE + windowMillis) it - windowMillis else Long.MIN_VALUE }
        val high = times.max()
        val rowsInRange =
            db
                .query(
                    "SELECT COUNT(*) FROM market_prices WHERE base_asset=? AND quote_asset=? " +
                        "AND valid_at>=CAST(? AS INTEGER) AND valid_at<=CAST(? AS INTEGER)",
                    listOf(base.value, quote.value, low.toString(), high.toString()),
                ).single()
                .single()!!
                .toLong()
        val found =
            if (rowsInRange <= times.size.toLong() * sources.size * rowsPerInstant) {
                latestInWindow(base, quote, times, knownAtMillis, sources, low, high)
            } else {
                latestPerInstant(base, quote, times, knownAtMillis, sources)
            }
        return times.mapIndexed { index, at ->
            val policy = policies[index]
            select((listOf<ProviderId?>(null) + policy.providerPriority).mapNotNull { found[it]?.get(at) }, at, policy)
        }
    }

    /** For each source, SQLite finds the newest usable observation at every instant with [latest]'s conditions. */
    private fun latestPerInstant(
        base: AssetId,
        quote: AssetId,
        times: List<Long>,
        knownAtMillis: Long,
        sources: List<ProviderId?>,
    ): Map<ProviderId?, Map<Long, PriceObservation>> {
        val distinct = times.distinct()
        return sources.associateWith { source ->
            val filter = if (source == null) "q.manual=1" else "q.manual=0 AND q.provider=?"
            val found = mutableMapOf<Long, PriceObservation>()
            // SQLite limits bound parameters, so long series are asked for in chunks.
            distinct.chunked(400).forEach { chunk ->
                val instants = chunk.joinToString(",") { "(CAST(? AS INTEGER))" }
                db
                    .query(
                        "WITH t(at) AS (VALUES $instants) SELECT t.at, p.id, p.value, p.provider, p.valid_at, p.observed_at, p.manual " +
                            "FROM t JOIN market_prices p ON p.id = (SELECT q.id FROM market_prices q " +
                            "WHERE q.base_asset=? AND q.quote_asset=? AND q.valid_at<=t.at AND q.observed_at<=CAST(? AS INTEGER) " +
                            "AND NOT EXISTS (SELECT 1 FROM market_override_withdrawals w WHERE w.price_id=q.id " +
                            "AND w.effective_at<=t.at AND w.observed_at<=CAST(? AS INTEGER)) AND $filter " +
                            "ORDER BY q.valid_at DESC, q.observed_at DESC, q.id ASC LIMIT 1)",
                        chunk.map { it.toString() } +
                            listOf(base.value, quote.value, knownAtMillis.toString(), knownAtMillis.toString()) +
                            listOfNotNull(source?.value),
                    ).forEach { row ->
                        found[row[0]!!.toLong()] =
                            PriceObservation(
                                row[1]!!,
                                Price(base, quote, ExactDecimal.parse(row[2]!!)),
                                row[3]?.let(::ProviderId),
                                row[4]!!.toLong(),
                                row[5]!!.toLong(),
                                row[6] == "1",
                            )
                    }
            }
            found
        }
    }

    /**
     * Reads the rows valid in [low]..[high] once and resolves each instant in memory, parsing only selected rows. An
     * instant with nothing usable in the window falls back to [latest] when older rows exist, so results match.
     */
    private fun latestInWindow(
        base: AssetId,
        quote: AssetId,
        times: List<Long>,
        knownAtMillis: Long,
        sources: List<ProviderId?>,
        low: Long,
        high: Long,
    ): Map<ProviderId?, Map<Long, PriceObservation>> {
        val rows =
            db
                .query(
                    "SELECT id, value, provider, valid_at, observed_at, manual FROM market_prices WHERE base_asset=? AND quote_asset=? " +
                        "AND valid_at>=CAST(? AS INTEGER) AND valid_at<=CAST(? AS INTEGER) AND observed_at<=CAST(? AS INTEGER)",
                    listOf(base.value, quote.value, low.toString(), high.toString(), knownAtMillis.toString()),
                ).map { WindowRow(it[0]!!, it[1]!!, it[2]?.let(::ProviderId), it[3]!!.toLong(), it[4]!!.toLong(), it[5] == "1") }
        val withdrawals =
            db
                .query(
                    "SELECT w.price_id, w.effective_at FROM market_override_withdrawals w JOIN market_prices p ON p.id=w.price_id " +
                        "WHERE p.base_asset=? AND p.quote_asset=? AND w.observed_at<=CAST(? AS INTEGER)",
                    listOf(base.value, quote.value, knownAtMillis.toString()),
                ).groupBy({ it[0]!! }, { it[1]!!.toLong() })
                .mapValues { (_, at) -> at.min() }
        val order = compareByDescending<WindowRow> { it.validAt }.thenByDescending { it.observedAt }.thenBy { it.id }
        // Manual observations form one source; provider observations one source per provider, as in [lookup].
        val bySource =
            rows
                .filter { it.manual || it.provider != null }
                .groupBy { if (it.manual) null else it.provider }
                .mapValues { (_, list) -> list.sortedWith(order) }
        val parsed = mutableMapOf<String, PriceObservation>()

        fun observation(row: WindowRow) =
            parsed.getOrPut(row.id) {
                PriceObservation(
                    row.id,
                    Price(base, quote, ExactDecimal.parse(row.value)),
                    row.provider,
                    row.validAt,
                    row.observedAt,
                    row.manual,
                )
            }
        val olderData = mutableMapOf<ProviderId?, Boolean>()

        // A source with no observation before the window can never match there, so it needs no per-instant fallback.
        fun hasOlder(source: ProviderId?): Boolean =
            olderData.getOrPut(source) {
                val filter = if (source == null) "manual=1" else "manual=0 AND provider=?"
                db
                    .query(
                        "SELECT 1 FROM market_prices WHERE base_asset=? AND quote_asset=? AND valid_at<CAST(? AS INTEGER) " +
                            "AND observed_at<=CAST(? AS INTEGER) AND $filter LIMIT 1",
                        listOf(base.value, quote.value, low.toString(), knownAtMillis.toString()) + listOfNotNull(source?.value),
                    ).isNotEmpty()
            }

        fun inWindow(
            source: ProviderId?,
            at: Long,
        ): PriceObservation? {
            val list = bySource[source].orEmpty()
            // Descending by validity: skip entries valid after this instant, then take the first not yet withdrawn.
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (list[mid].validAt > at) lo = mid + 1 else hi = mid
            }
            for (index in lo until list.size) {
                val candidate = list[index]
                val withdrawnAt = withdrawals[candidate.id]
                if (withdrawnAt == null || withdrawnAt > at) return observation(candidate)
            }
            return if (hasOlder(source)) latest(base, quote, at, knownAtMillis, source) else null
        }
        val distinct = times.distinct()
        return sources.associateWith { source -> distinct.mapNotNull { at -> inWindow(source, at)?.let { at to it } }.toMap() }
    }

    /** Every external ID [provider] maps to a portfolio asset, in one query. */
    fun providerMappings(provider: ProviderId): Map<String, AssetId> =
        db
            .query("SELECT external_id, asset_id FROM market_provider_mappings WHERE provider=?", listOf(provider.value))
            .associate { it[0]!! to AssetId(it[1]!!) }

    /** A price row read for a window, kept as text until an instant selects it. */
    private class WindowRow(
        val id: String,
        val value: String,
        val provider: ProviderId?,
        val validAt: Long,
        val observedAt: Long,
        val manual: Boolean,
    )

    private fun requireAsset(id: AssetId) {
        require(db.query("SELECT id FROM market_assets WHERE id=?", listOf(id.value)).isNotEmpty()) { "Unknown asset" }
    }
}
