package io.polion.core

/** Fixed events keep portfolio data, credentials and arbitrary messages out of logs. */
enum class LogEvent {
    APPLICATION_STARTED,
}

fun interface Logger {
    fun log(event: LogEvent)
}

object ConsoleLogger : Logger {
    override fun log(event: LogEvent) {
        println("polion:${event.name}")
    }
}
