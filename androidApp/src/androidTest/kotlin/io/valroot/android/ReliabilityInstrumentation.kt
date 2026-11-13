package io.valroot.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Exercises the actual Android SQLite and crypto bindings using disposable fixture databases. */
class ReliabilityInstrumentation : Instrumentation() {
    private var livePrices = false
    private var benchmark = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        livePrices = arguments?.getString("live_prices") == "true"
        benchmark = arguments?.getString("benchmark") == "chart"
        start()
    }

    override fun onStart() {
        if (benchmark) {
            chartBenchmark()
            return
        }
        val results = Bundle()
        var failures = 0
        val checks =
            backgroundChecks() +
                sqliteChecks(targetContext) +
                applicationChecks(targetContext) +
                backupChecks(targetContext) +
                securityChecks(targetContext) +
                if (livePrices) livePriceChecks(targetContext) else emptyList()
        checks.forEach { (name, check) ->
            try {
                check()
                results.putString(name, "PASS")
            } catch (failure: Throwable) {
                failures++
                results.putString(name, failure.stackTraceToString())
            }
        }
        results.putInt("checks", checks.size)
        results.putInt("failures", failures)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }
}

/** A named check: its key in the results Bundle and the body that throws on failure. */
internal typealias ReliabilityCheck = Pair<String, () -> Unit>
