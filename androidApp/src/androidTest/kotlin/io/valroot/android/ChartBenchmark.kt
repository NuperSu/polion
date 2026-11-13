package io.valroot.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.AndroidSqliteDatabase
import java.io.File

/**
 * Times the queries behind a chart period change on a copy of this user's database, without the UI. Run with
 * `-e benchmark chart`; results are milliseconds in the instrumentation output.
 */
internal fun Instrumentation.chartBenchmark() {
    val results = Bundle()
    val source = targetContext.getDatabasePath("portfolio.sqlite")
    val copy = File(targetContext.cacheDir, "chart-benchmark.sqlite")
    try {
        check(source.exists()) { "No portfolio database for this user" }
        source.copyTo(copy, overwrite = true)
        listOf("-wal", "-shm").forEach { suffix ->
            File(source.path + suffix).takeIf { it.exists() }?.copyTo(File(copy.path + suffix), overwrite = true)
        }

        fun <T> time(
            name: String,
            block: () -> T,
        ): T {
            val start = System.nanoTime()
            val value = block()
            results.putLong(name, (System.nanoTime() - start) / 1_000_000L)
            return value
        }
        AndroidSqliteDatabase(copy.path).use { db ->
            val app = time("open") { PortfolioApplication(db, System::currentTimeMillis) }
            time("snapshot") { app.snapshot() }
            time("snapshotAgain") { app.snapshot() }
            time("trivialQueries100") { repeat(100) { db.query("SELECT value FROM application_settings WHERE key='currency'") } }
            val end = System.currentTimeMillis()
            repeat(2) { round ->
                for ((label, days, intervals) in listOf(
                    Triple("1D", 1L, 96),
                    Triple("1W", 7L, 84),
                    Triple("1M", 30L, 90),
                    Triple("1Y", 365L, 104),
                )) {
                    val start = end - days * 86_400_000L
                    val times = (0..intervals).map { start + (end - start) / intervals * it }
                    time("history$label.$round") { app.analytics.historySeries(times, null) }
                }
            }
            time("currency") { app.assets.displayCurrency(null) }
            time("firstActivity") { app.analytics.firstHistoryActivity(null) }
            time("priceSeries97") {
                app.analytics.priceSeries(
                    io.valroot.core.bitcoin.BITCOIN_ASSET,
                    app.assets.currency(),
                    (0..96).map {
                        end - 86_400_000L +
                            900_000L * it
                    },
                )
            }
            time("marketOverview") { app.market.overview() }
            time("marketOverviewAgain") { app.market.overview() }
            time("accountingReport") { app.accounting.report(null) }
            time("reload+snapshot") {
                app.reload()
                app.snapshot()
            }
        }
        finish(Activity.RESULT_OK, results)
    } catch (failure: Exception) {
        results.putString("error", failure.toString())
        finish(Activity.RESULT_CANCELED, results)
    } finally {
        listOf("", "-wal", "-shm", "-journal").forEach { File(copy.path + it).delete() }
    }
}
