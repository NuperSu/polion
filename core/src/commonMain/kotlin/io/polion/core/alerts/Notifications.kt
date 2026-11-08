package io.polion.core.alerts

import io.polion.core.model.ExactDecimal
import io.polion.core.model.Rounding
import io.polion.core.storage.SqliteDatabase

/** What a price alert watches: a price at or above, at or below, or a move of a percentage from its reference price. */
enum class AlertCondition { ABOVE, BELOW, MOVE }

/**
 * A price alert on one CoinGecko coin in one list currency, with its delivery state.
 *
 * - ABOVE and BELOW fire when the price reaches [threshold] while [armed], then disarm; they re-arm when the price is back
 *   on the other side, so each crossing alerts once.
 * - MOVE fires when the price is [threshold] percent or more away from [reference]; the reference then resets to that price.
 * - Each rule alerts at most once per [AlertRules.COOLDOWN_MILLIS], and only once per market snapshot ([lastObservedMillis]).
 */
data class PriceAlert(
    val id: String,
    val coinId: String,
    val symbol: String,
    /** Lower-case list currency, for example "usd". */
    val currency: String,
    val condition: AlertCondition,
    /** A price for ABOVE and BELOW; a percentage for MOVE. */
    val threshold: ExactDecimal,
    val enabled: Boolean,
    val armed: Boolean,
    val reference: ExactDecimal?,
    val referenceAtMillis: Long?,
    val lastObservedMillis: Long?,
    val lastFiredMillis: Long?,
    val createdAtMillis: Long,
)

enum class NotificationKind { PRICE_ALERT, DAILY_UPDATE, PRICE_MOVE, ALL_TIME_HIGH, ALL_TIME_LOW }

/** A delivered notification; [key] is unique, so the same event is never delivered twice, including after a restart. */
data class NotificationMessage(
    val key: String,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val atMillis: Long,
    val alertId: String? = null,
    val coinId: String? = null,
)

/** The Notifications settings. Each is off until the user turns it on. */
data class NotificationPreferences(
    val dailyUpdate: Boolean = false,
    val priceMoves: Boolean = false,
    val highsAndLows: Boolean = false,
)

object AlertRules {
    const val COOLDOWN_MILLIS = 3_600_000L

    /** Market prices older than this are not evaluated, so a stale list never fires an alert. */
    const val MAX_PRICE_AGE_MILLIS = 3_600_000L

    data class Step(
        val alert: PriceAlert,
        val fired: Boolean,
    )

    /** Initial state for a new or edited rule, given the current price when one is known. */
    fun start(
        alert: PriceAlert,
        price: ExactDecimal?,
        observedAtMillis: Long?,
    ): PriceAlert =
        when (alert.condition) {
            // A rule whose condition already holds waits for the next crossing instead of firing at once.
            AlertCondition.ABOVE -> alert.copy(armed = price == null || price < alert.threshold)
            AlertCondition.BELOW -> alert.copy(armed = price == null || price > alert.threshold)
            AlertCondition.MOVE -> alert.copy(armed = true, reference = price, referenceAtMillis = observedAtMillis)
        }.copy(lastObservedMillis = observedAtMillis, lastFiredMillis = null)

    /** Applies one market observation to [alert]. A snapshot already seen, or a disabled rule, changes nothing. */
    fun step(
        alert: PriceAlert,
        price: ExactDecimal,
        observedAtMillis: Long,
        nowMillis: Long,
    ): Step {
        if (!alert.enabled || alert.lastObservedMillis?.let { observedAtMillis <= it } == true) return Step(alert, false)
        val seen = alert.copy(lastObservedMillis = observedAtMillis)
        val cooling = alert.lastFiredMillis?.let { nowMillis - it < COOLDOWN_MILLIS } == true
        return when (alert.condition) {
            AlertCondition.ABOVE, AlertCondition.BELOW -> {
                val met = if (alert.condition == AlertCondition.ABOVE) price >= alert.threshold else price <= alert.threshold
                when {
                    !met -> Step(seen.copy(armed = true), false)
                    alert.armed && !cooling -> Step(seen.copy(armed = false, lastFiredMillis = nowMillis), true)
                    else -> Step(seen, false)
                }
            }
            AlertCondition.MOVE -> {
                val reference = alert.reference
                if (reference == null) {
                    Step(seen.copy(reference = price, referenceAtMillis = observedAtMillis), false)
                } else if (!cooling && kotlin.math.abs(percentChange(reference, price)) >= alert.threshold.toDouble()) {
                    Step(seen.copy(reference = price, referenceAtMillis = observedAtMillis, lastFiredMillis = nowMillis), true)
                } else {
                    Step(seen, false)
                }
            }
        }
    }

    fun percentChange(
        from: ExactDecimal,
        to: ExactDecimal,
    ): Double = (to.toDouble() / from.toDouble() - 1) * 100

    private fun ExactDecimal.toDouble() = toString().toDouble()
}

/** Plain-text figures for notification text, which is built without the app's display formatting. */
object NotificationText {
    fun price(
        value: ExactDecimal,
        currency: String,
    ): String {
        val one = ExactDecimal.parse("1")
        val digits = if (value >= one) 2 else 8
        val text = value.rescale(digits, Rounding.HALF_UP).toString()
        val whole = text.substringBefore('.')
        val fraction = text.substringAfter('.', "").let { if (value >= one) it else it.trimEnd('0') }
        val grouped =
            whole
                .reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()
                .replace("-,", "-")
        return (if (fraction.isEmpty()) grouped else "$grouped.$fraction") + " " + currency.uppercase()
    }

    fun percent(value: Double): String {
        val rounded = kotlin.math.round(value * 10) / 10
        return (if (rounded > 0) "+" else "") + rounded.toString().removeSuffix(".0") + "%"
    }

    fun describe(alert: PriceAlert): String =
        when (alert.condition) {
            AlertCondition.ABOVE -> "Price at or above ${price(alert.threshold, alert.currency)}"
            AlertCondition.BELOW -> "Price at or below ${price(alert.threshold, alert.currency)}"
            AlertCondition.MOVE ->
                "Moves ${alert.threshold}% from " + (alert.reference?.let { price(it, alert.currency) } ?: "the next price")
        }
}

/** Price alerts and the delivery history; both are part of encrypted backups. */
class NotificationRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS price_alerts (id TEXT PRIMARY KEY, coin_id TEXT NOT NULL, symbol TEXT NOT NULL, " +
                "currency TEXT NOT NULL, condition TEXT NOT NULL, threshold TEXT NOT NULL, enabled INTEGER NOT NULL, " +
                "armed INTEGER NOT NULL, reference TEXT, reference_at INTEGER, last_observed INTEGER, last_fired INTEGER, created_at INTEGER NOT NULL)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS notification_history (key TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, " +
                "body TEXT NOT NULL, at_millis INTEGER NOT NULL, alert_id TEXT, coin_id TEXT)",
        )
    }

    fun alerts(): List<PriceAlert> =
        db.query("SELECT * FROM price_alerts ORDER BY created_at, id").map { row ->
            PriceAlert(
                id = row[0]!!,
                coinId = row[1]!!,
                symbol = row[2]!!,
                currency = row[3]!!,
                condition = AlertCondition.valueOf(row[4]!!),
                threshold = ExactDecimal.parse(row[5]!!),
                enabled = row[6] == "1",
                armed = row[7] == "1",
                reference = row[8]?.let(ExactDecimal::parse),
                referenceAtMillis = row[9]?.toLong(),
                lastObservedMillis = row[10]?.toLong(),
                lastFiredMillis = row[11]?.toLong(),
                createdAtMillis = row[12]!!.toLong(),
            )
        }

    fun save(alert: PriceAlert) =
        db.execute(
            "INSERT OR REPLACE INTO price_alerts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                alert.id,
                alert.coinId,
                alert.symbol,
                alert.currency,
                alert.condition.name,
                alert.threshold.toString(),
                if (alert.enabled) "1" else "0",
                if (alert.armed) "1" else "0",
                alert.reference?.toString(),
                alert.referenceAtMillis?.toString(),
                alert.lastObservedMillis?.toString(),
                alert.lastFiredMillis?.toString(),
                alert.createdAtMillis.toString(),
            ),
        )

    fun delete(id: String) = db.execute("DELETE FROM price_alerts WHERE id=?", listOf(id))

    fun delivered(key: String): Boolean = db.query("SELECT 1 FROM notification_history WHERE key=?", listOf(key)).isNotEmpty()

    /** Records [message]; false when its key was already delivered. */
    fun record(message: NotificationMessage): Boolean {
        if (delivered(message.key)) return false
        db.execute(
            "INSERT INTO notification_history VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf(
                message.key,
                message.kind.name,
                message.title,
                message.body,
                message.atMillis.toString(),
                message.alertId,
                message.coinId,
            ),
        )
        return true
    }

    fun history(limit: Int): List<NotificationMessage> =
        db
            .query(
                "SELECT key, kind, title, body, at_millis, alert_id, coin_id FROM notification_history ORDER BY at_millis DESC, key LIMIT ?",
                listOf(limit.toString()),
            ).map { row ->
                NotificationMessage(row[0]!!, NotificationKind.valueOf(row[1]!!), row[2]!!, row[3]!!, row[4]!!.toLong(), row[5], row[6])
            }

    /** Keeps the newest [keep] deliveries, so the history stays small. */
    fun trim(keep: Int) =
        db.execute(
            "DELETE FROM notification_history WHERE key NOT IN (SELECT key FROM notification_history ORDER BY at_millis DESC, key LIMIT ?)",
            listOf(keep.toString()),
        )
}
