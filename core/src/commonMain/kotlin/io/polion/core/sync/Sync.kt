package io.polion.core.sync

import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryFetcher
import io.polion.core.connectors.HistoryPage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import io.polion.core.model.SyncJobId
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store

fun interface SyncClock {
    fun nowMillis(): Long
}

data class SyncProvider(
    val id: ProviderId,
    val fetcher: HistoryFetcher,
    val networks: Set<NetworkId>,
    val sources: Set<SourceId>,
    val minimumIntervalMillis: Long = 0,
) {
    init {
        require(minimumIntervalMillis >= 0)
        require(networks.isNotEmpty() && sources.isNotEmpty())
    }
}

data class SyncSettings(
    val maxConcurrent: Int = 2,
    val leaseMillis: Long = 60_000,
    val retryBaseMillis: Long = 1_000,
    val retryMaxMillis: Long = 300_000,
    val maxAttempts: Int = 6,
) {
    init {
        require(maxConcurrent > 0 && leaseMillis > 0 && retryBaseMillis > 0 && retryMaxMillis >= retryBaseMillis && maxAttempts > 0)
    }
}

enum class JobState { READY, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

data class SyncJob(
    val id: SyncJobId,
    val source: SourceId,
    val network: NetworkId,
    val account: AccountId,
    val overlap: String? = null,
    val finality: String? = null,
)

data class SyncStatus(
    val state: JobState,
    val attempts: Int,
    val nextRunMillis: Long,
    val pages: Int,
    val coverage: Coverage,
    val cursor: String?,
    val lastProvider: ProviderId?,
)

/** A fetch is bounded by the caller's transport timeout. Run one page per call, on a worker thread. */
class SyncOrchestrator(
    private val db: SqliteDatabase,
    private val store: Store,
    providers: List<SyncProvider>,
    private val clock: SyncClock,
    private val settings: SyncSettings = SyncSettings(),
) {
    private val providers = providers.toList()

    init {
        require(providers.isNotEmpty() && providers.map { it.id }.toSet().size == providers.size)
        db.transaction {
            db.execute("CREATE TABLE IF NOT EXISTS sync_schema (version INTEGER NOT NULL)")
            val version = db.query("SELECT version FROM sync_schema")
            if (version.isEmpty()) {
                db.execute("INSERT INTO sync_schema VALUES (1)")
            } else {
                require(version.size == 1 && version.single().single() == "1") { "Unsupported sync schema" }
            }
            db.execute(
                "CREATE TABLE IF NOT EXISTS sync_jobs (id TEXT PRIMARY KEY, source TEXT NOT NULL, network TEXT NOT NULL, account TEXT NOT NULL, overlap TEXT, finality TEXT, state TEXT NOT NULL, attempts INTEGER NOT NULL, next_run INTEGER NOT NULL, lease_until INTEGER NOT NULL, token INTEGER NOT NULL, pages INTEGER NOT NULL, coverage TEXT NOT NULL, cursor TEXT, last_provider TEXT, cursor_provider TEXT)",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS sync_provider_health (provider TEXT PRIMARY KEY, available_at INTEGER NOT NULL, failures INTEGER NOT NULL)",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS sync_errors (job_id TEXT NOT NULL REFERENCES sync_jobs(id), at_millis INTEGER NOT NULL, provider TEXT NOT NULL, kind TEXT NOT NULL)",
            )
            db.execute(
                "CREATE TABLE IF NOT EXISTS sync_cursors (job_id TEXT NOT NULL REFERENCES sync_jobs(id), provider TEXT NOT NULL, cursor TEXT NOT NULL, PRIMARY KEY(job_id, provider, cursor))",
            )
        }
    }

    fun enqueue(job: SyncJob) =
        db.transaction {
            require(providers.any { job.network in it.networks && job.source in it.sources }) {
                "No provider supports the job source and network"
            }
            db.execute(
                "INSERT INTO sync_jobs VALUES (?, ?, ?, ?, ?, ?, 'READY', 0, 0, 0, 0, 0, 'UNKNOWN', NULL, NULL, NULL)",
                listOf(job.id.value, job.source.value, job.network.value, job.account.value, job.overlap, job.finality),
            )
        }

    fun status(id: SyncJobId): SyncStatus? =
        db
            .query(
                "SELECT state, attempts, next_run, pages, coverage, cursor, last_provider FROM sync_jobs WHERE id=?",
                listOf(id.value),
            ).singleOrNull()
            ?.let { row ->
                SyncStatus(
                    JobState.valueOf(row[0]!!),
                    row[1]!!.toInt(),
                    row[2]!!.toLong(),
                    row[3]!!.toInt(),
                    Coverage.valueOf(row[4]!!),
                    row[5],
                    row[6]?.let(::ProviderId),
                )
            }

    fun cancel(id: SyncJobId) =
        db.transaction {
            db.execute(
                "UPDATE sync_jobs SET state='CANCELLED', token=token+1 WHERE id=? AND state IN ('READY','RUNNING','PARTIAL','FAILED')",
                listOf(id.value),
            )
        }

    /** An explicit retry clears job and provider backoff for jobs that are not running; a running job's lease remains authoritative. */
    fun retry(id: SyncJobId) =
        db.transaction {
            db.execute(
                "UPDATE sync_provider_health SET available_at=0 WHERE provider=(SELECT last_provider FROM sync_jobs WHERE id=? AND state IN ('READY','FAILED','PARTIAL','CANCELLED'))",
                listOf(id.value),
            )
            db.execute("DELETE FROM sync_cursors WHERE job_id=?", listOf(id.value))
            db.execute(
                "UPDATE sync_jobs SET state='READY', attempts=0, next_run=0, lease_until=0, cursor=NULL, cursor_provider=NULL, token=token+1 WHERE id=? AND state IN ('READY','FAILED','PARTIAL','CANCELLED')",
                listOf(id.value),
            )
        }

    /** Resume safely after process death by reclaiming jobs whose lease expired. */
    fun runOne(): Boolean {
        val claim = claim() ?: return false
        val page =
            try {
                claim.provider.fetcher.fetch(claim.request)
            } catch (failure: Exception) {
                fail(claim, "FETCH")
                return true
            }
        try {
            commit(claim, page)
        } catch (failure: Exception) {
            fail(claim, "COMMIT")
        }
        return true
    }

    private data class Claim(
        val job: SyncJobId,
        val token: Long,
        val provider: SyncProvider,
        val request: HistoryRequest,
    )

    private fun claim(): Claim? =
        db.transaction {
            val now = clock.nowMillis()
            db.execute(
                "UPDATE sync_jobs SET state='READY', lease_until=0, token=token+1 WHERE state='RUNNING' AND lease_until<=?",
                listOf(now.toString()),
            )
            val running =
                db
                    .query("SELECT COUNT(*) FROM sync_jobs WHERE state='RUNNING'")
                    .single()
                    .single()!!
                    .toInt()
            if (running >= settings.maxConcurrent) return@transaction null
            val candidates =
                db.query(
                    "SELECT id, source, network, account, cursor, overlap, finality, token, last_provider, attempts, cursor_provider FROM sync_jobs j WHERE state='READY' AND next_run<=? AND NOT EXISTS (SELECT 1 FROM sync_jobs r WHERE r.state='RUNNING' AND r.source=j.source AND r.network=j.network AND r.account=j.account) ORDER BY next_run, id",
                    listOf(now.toString()),
                )
            for (row in candidates) {
                val lastProvider = row[8]
                val start =
                    providers.indexOfFirst { it.id.value == lastProvider }.let {
                        if (it < 0) {
                            0
                        } else if (row[9] == "0") {
                            it
                        } else {
                            (it + 1) % providers.size
                        }
                    }
                val ordered = providers.indices.map { providers[(start + it) % providers.size] }
                val provider =
                    ordered.firstOrNull {
                        if (NetworkId(row[2]!!) !in it.networks || SourceId(row[1]!!) !in it.sources) return@firstOrNull false
                        val available =
                            db
                                .query(
                                    "SELECT available_at FROM sync_provider_health WHERE provider=?",
                                    listOf(it.id.value),
                                ).singleOrNull()
                                ?.single()
                                ?.toLong()
                                ?: 0
                        available <= now
                    } ?: continue
                val token = row[7]!!.toLong() + 1
                if (row[10] != provider.id.value) {
                    db.execute("DELETE FROM sync_cursors WHERE job_id=? AND provider=?", listOf(row[0], provider.id.value))
                }
                db.execute(
                    "UPDATE sync_jobs SET state='RUNNING', lease_until=?, token=?, last_provider=? WHERE id=?",
                    listOf(
                        (
                            now +
                                settings.leaseMillis
                        ).toString(),
                        token.toString(),
                        provider.id.value,
                        row[0],
                    ),
                )
                db.execute("INSERT OR IGNORE INTO sync_provider_health VALUES (?, 0, 0)", listOf(provider.id.value))
                db.execute(
                    "UPDATE sync_provider_health SET available_at=? WHERE provider=?",
                    listOf(
                        (
                            now +
                                provider.minimumIntervalMillis
                        ).toString(),
                        provider.id.value,
                    ),
                )
                return@transaction Claim(
                    SyncJobId(row[0]!!),
                    token,
                    provider,
                    HistoryRequest(
                        SourceId(row[1]!!),
                        provider.id,
                        NetworkId(row[2]!!),
                        AccountId(row[3]!!),
                        if (row[10] ==
                            provider.id.value
                        ) {
                            row[4]
                        } else {
                            null
                        },
                        row[5],
                        row[6],
                    ),
                )
            }
            null
        }

    private fun commit(
        claim: Claim,
        page: HistoryPage,
    ) = db.transaction {
        if (!owns(claim)) return@transaction
        require(page.nextCursor == null || page.nextCursor != claim.request.cursor) { "Provider repeated pagination cursor" }
        page.nextCursor?.let { next ->
            val args = listOf(claim.job.value, claim.provider.id.value, next)
            require(
                db.query("SELECT 1 FROM sync_cursors WHERE job_id=? AND provider=? AND cursor=?", args).isEmpty(),
            ) { "Pagination cycle" }
            db.execute("INSERT INTO sync_cursors VALUES (?, ?, ?)", args)
        }
        require(page.observations.all { it.sourceId == claim.request.source.value && it.network == claim.request.network.value }) {
            "Provider returned a record for a different source or network"
        }
        page.observations.forEach(store::ingestInTransaction)
        val state =
            when {
                page.nextCursor != null -> JobState.READY
                page.coverage == Coverage.COMPLETE -> JobState.COMPLETED
                else -> JobState.PARTIAL
            }
        db.execute(
            "UPDATE sync_jobs SET state=?, cursor=?, cursor_provider=?, coverage=?, pages=pages+1, attempts=0, next_run=0, lease_until=0 WHERE id=? AND token=?",
            listOf(state.name, page.nextCursor, claim.provider.id.value, page.coverage.name, claim.job.value, claim.token.toString()),
        )
        db.execute("UPDATE sync_provider_health SET failures=0 WHERE provider=?", listOf(claim.provider.id.value))
    }

    private fun fail(
        claim: Claim,
        kind: String,
    ) = db.transaction {
        if (!owns(claim)) return@transaction
        val attempts =
            db
                .query("SELECT attempts FROM sync_jobs WHERE id=?", listOf(claim.job.value))
                .single()
                .single()!!
                .toInt() + 1
        var delay = settings.retryBaseMillis
        repeat((attempts - 1).coerceAtMost(62)) { delay = if (delay >= settings.retryMaxMillis / 2) settings.retryMaxMillis else delay * 2 }
        val now = clock.nowMillis()
        val next = now + delay.coerceAtMost(settings.retryMaxMillis)
        db.execute("INSERT INTO sync_errors VALUES (?, ?, ?, ?)", listOf(claim.job.value, now.toString(), claim.provider.id.value, kind))
        db.execute(
            "UPDATE sync_jobs SET state=?, attempts=?, next_run=?, lease_until=0 WHERE id=?",
            listOf(
                if (attempts >=
                    settings.maxAttempts
                ) {
                    "FAILED"
                } else {
                    "READY"
                },
                attempts.toString(),
                next.toString(),
                claim.job.value,
            ),
        )
        db.execute(
            "UPDATE sync_provider_health SET available_at=MAX(available_at, CAST(? AS INTEGER)), failures=failures+1 WHERE provider=?",
            listOf(next.toString(), claim.provider.id.value),
        )
    }

    private fun owns(claim: Claim): Boolean =
        db
            .query("SELECT state, token, lease_until FROM sync_jobs WHERE id=?", listOf(claim.job.value))
            .singleOrNull()
            ?.let {
                it[0] == "RUNNING" && it[1] == claim.token.toString() && it[2]!!.toLong() > clock.nowMillis()
            } == true
}
