package io.valroot.desktop

import androidx.compose.runtime.MutableState
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.DefiLlamaTransport
import io.valroot.core.market.HttpCoingeckoTransport
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.PriceRefreshPhase
import io.valroot.ui.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State shared by [DesktopHost] and its helpers: the database file and its connections, the workers and transports, and the
 * flags that keep background work out of a restore and away from a closed app. Helpers receive it through their constructors.
 */
internal class DesktopState(
    val directory: Path,
) {
    val file: Path = directory.resolve("portfolio.sqlite")

    /** Main background worker: backup and lock-file work, the wallet-history schedule and close. */
    val executor = Executors.newSingleThreadScheduledExecutor()
    val unlockExecutor = Executors.newSingleThreadExecutor()
    val priceExecutor = Executors.newSingleThreadScheduledExecutor()

    /** Linked-coin history backfills run here, one at a time, so they never hold up a refresh's result. */
    val historyExecutor = Executors.newSingleThreadExecutor()
    val syncQueue = BitcoinSyncQueue()
    val priceTransport = HttpCoingeckoTransport()
    val defiLlamaTransport = DefiLlamaTransport()
    val closing = AtomicBoolean(false)
    val dataRevision = MutableStateFlow(0L)
    val syncStatus = MutableStateFlow(SyncStatus())
    val priceRefreshStatus = MutableStateFlow(PriceRefreshPhase.IDLE)

    /** Held by price, market and notification work while it reads or writes, and by a restore while it swaps the file. */
    val priceGate = Any()

    /** True from the start of a restore until its file swap is finished; background price work skips while it is set. */
    @Volatile var priceRestorePaused = false

    /** Guards the sync connection and the notification schedule. */
    val hostLock = Any()

    lateinit var db: JvmSqliteDatabase
    lateinit var app: MutableState<PortfolioApplication>
    private var syncDb: JvmSqliteDatabase? = null
    private var syncApp: PortfolioApplication? = null

    /** The sync worker's own connection, opened on first use and reused until a restore or close releases it. */
    fun syncApplication(): PortfolioApplication =
        synchronized(hostLock) {
            syncApp ?: run {
                val connection = JvmSqliteDatabase(file.toString())
                syncDb = connection
                PortfolioApplication(connection, System::currentTimeMillis).also { syncApp = it }
            }
        }

    fun closeSyncConnection() {
        synchronized(hostLock) {
            syncApp = null
            syncDb?.close()
            syncDb = null
        }
    }
}
