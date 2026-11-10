package io.polion.android

import android.app.Activity
import android.content.Intent
import io.polion.ui.SyncPhase
import io.polion.ui.SyncStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Debug-only foreground-service fixture: no database, transport or wallet operations. */
class SyncServiceProbeActivity : Activity()

class SyncServiceProbeState {
    val delivered = CountDownLatch(3)
    val firstStarted = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val thirdStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val releaseSecond = CountDownLatch(1)
    val releaseThird = CountDownLatch(1)
    val destroyed = CountDownLatch(1)
    val started = CopyOnWriteArrayList<String>()
    var instances = 0

    fun releaseAll() {
        releaseFirst.countDown()
        releaseSecond.countDown()
        releaseThird.countDown()
    }
}

class SyncServiceProbe : BitcoinRefreshService() {
    companion object {
        @Volatile var state = SyncServiceProbeState()
    }

    private val scenario = state
    private val executor = Executors.newSingleThreadExecutor()
    override val workExecutor get() = executor

    override fun onCreate() {
        super.onCreate()
        scenario.instances++
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = super.onStartCommand(intent, flags, startId).also { scenario.delivered.countDown() }

    override fun runRefresh(
        walletId: String?,
        verifyOrder: Boolean,
        recoverHistory: Boolean,
        evmOnly: Boolean,
        onProgress: (String) -> Unit,
    ): SyncStatus {
        check(!verifyOrder && !recoverHistory && !evmOnly)
        val key = walletId ?: "history"
        scenario.started += key
        val (started, release) =
            when (key) {
                "history" -> scenario.firstStarted to scenario.releaseFirst
                "wallet-a" -> scenario.secondStarted to scenario.releaseSecond
                "wallet-b" -> scenario.thirdStarted to scenario.releaseThird
                else -> error("Unexpected fixture request")
            }
        started.countDown()
        check(release.await(15, TimeUnit.SECONDS)) { "Fixture gate timed out" }
        return SyncStatus(if (walletId == null) SyncPhase.ERROR else SyncPhase.SUCCESS, "Fixture request finished")
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        scenario.destroyed.countDown()
    }
}
