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
                db.execute("INSERT INTO market_schema VALUES (1)")
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
            } else {
                require(rows.size == 1 && rows.single().single() == "1") { "Unsupported market schema" }
                val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it.single() }.toSet()
                check(tables.containsAll(setOf("market_assets", "market_metadata", "market_provider_mappings", "market_prices"))) {
                    "Market schema is incomplete"
                }
            }
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

    fun recordPrice(observation: PriceObservation) =
        db.transaction {
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

    /** Select manual override first; other observations follow freshness and provider policy. */
    fun lookup(
        base: AssetId,
        quote: AssetId,
        atMillis: Long,
        knownAtMillis: Long,
        policy: PricePolicy,
    ): PriceResult {
        require(base != quote)
        val observations =
            db
                .query(
                    "SELECT id, value, provider, valid_at, observed_at, manual FROM market_prices WHERE base_asset=? AND quote_asset=? AND valid_at<=CAST(? AS INTEGER) AND observed_at<=CAST(? AS INTEGER)",
                    listOf(base.value, quote.value, atMillis.toString(), knownAtMillis.toString()),
                ).map { row ->
                    PriceObservation(
                        row[0]!!,
                        Price(base, quote, ExactDecimal.parse(row[1]!!)),
                        row[2]?.let(::ProviderId),
                        row[3]!!.toLong(),
                        row[4]!!.toLong(),
                        row[5] == "1",
                    )
                }.filter { it.manual || it.providerId in policy.providerPriority }

        fun isStale(observation: PriceObservation): Boolean =
            atMillis >= Long.MIN_VALUE + policy.maxAgeMillis && observation.validAtMillis < atMillis - policy.maxAgeMillis
        val selected =
            observations.minWithOrNull(
                compareBy<PriceObservation> { if (it.manual) 0 else 1 }
                    .thenBy { if (!it.manual && policy.preferFresh && isStale(it)) 1 else 0 }
                    .thenBy { if (it.manual) -1 else policy.providerPriority.indexOf(it.providerId) }
                    .thenByDescending { it.validAtMillis }
                    .thenByDescending { it.observedAtMillis }
                    .thenBy { it.id },
            ) ?: return PriceResult(PriceStatus.MISSING, null)
        val stale = isStale(selected)
        return PriceResult(if (stale) PriceStatus.STALE else PriceStatus.FRESH, selected)
    }

    private fun requireAsset(id: AssetId) {
        require(db.query("SELECT id FROM market_assets WHERE id=?", listOf(id.value)).isNotEmpty()) { "Unknown asset" }
    }
}
