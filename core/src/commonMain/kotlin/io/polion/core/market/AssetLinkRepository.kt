package io.polion.core.market

import io.polion.core.model.AssetId
import io.polion.core.storage.SqliteDatabase

/**
 * Links from chain-specific assets to the CoinGecko coin they are (several EVM assets can be one coin, such as ETH on
 * Ethereum and on Base), and the user's choice to show or hide an asset. Both are part of encrypted backups.
 */
class AssetLinkRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS asset_coin_links (asset_id TEXT PRIMARY KEY, coin_id TEXT NOT NULL)")
        db.execute("CREATE INDEX IF NOT EXISTS asset_coin_links_coin ON asset_coin_links(coin_id)")
        db.execute("CREATE TABLE IF NOT EXISTS asset_visibility (asset_id TEXT PRIMARY KEY, shown INTEGER NOT NULL)")
    }

    fun link(
        asset: AssetId,
        coinId: String,
    ) = db.execute("INSERT OR REPLACE INTO asset_coin_links VALUES (?, ?)", listOf(asset.value, coinId))

    fun coinOf(asset: AssetId): String? =
        db.query("SELECT coin_id FROM asset_coin_links WHERE asset_id=?", listOf(asset.value)).singleOrNull()?.single()

    fun assetsOf(coinId: String): List<AssetId> =
        db.query("SELECT asset_id FROM asset_coin_links WHERE coin_id=? ORDER BY asset_id", listOf(coinId)).map { AssetId(it.single()!!) }

    fun links(): Map<AssetId, String> = db.query("SELECT asset_id, coin_id FROM asset_coin_links").associate { AssetId(it[0]!!) to it[1]!! }

    /** The user's explicit choice, or null when none was made. */
    fun shown(asset: AssetId): Boolean? =
        db
            .query("SELECT shown FROM asset_visibility WHERE asset_id=?", listOf(asset.value))
            .singleOrNull()
            ?.single()
            ?.let { it == "1" }

    fun choices(): Map<AssetId, Boolean> =
        db.query("SELECT asset_id, shown FROM asset_visibility").associate {
            AssetId(it[0]!!) to
                (it[1] == "1")
        }

    fun setShown(
        asset: AssetId,
        shown: Boolean,
    ) = db.execute("INSERT OR REPLACE INTO asset_visibility VALUES (?, ?)", listOf(asset.value, if (shown) "1" else "0"))
}
