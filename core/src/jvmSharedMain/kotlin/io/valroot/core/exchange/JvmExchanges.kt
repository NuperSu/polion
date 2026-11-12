package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceConnector
import io.valroot.core.exchange.binance.BinanceProvider
import io.valroot.core.exchange.binance.BinanceTransport
import io.valroot.core.exchange.bybit.BybitProvider
import io.valroot.core.exchange.bybit.BybitTransport
import io.valroot.core.exchange.mexc.MexcProvider
import io.valroot.core.exchange.mexc.MexcTransport
import io.valroot.core.model.AccountId

/** An exchange provider with the JVM transport that sends its signed requests. */
class ExchangeBinding<R>(
    val provider: ExchangeProvider<R>,
    private val transport: (credentials: (AccountId) -> Pair<String, String>?, beforeRequest: () -> Unit) -> ExchangeTransport<R>,
) {
    /** The connector for every tracked account of this exchange; [beforeRequest] may throw to cancel. */
    fun connector(
        app: PortfolioApplication,
        beforeRequest: () -> Unit,
    ): SourceConnector = provider.connector(transport({ app.exchanges.key(provider.kind, it) }, beforeRequest))

    /** Checks a key before an account is tracked: whose account it reads and whether it can only read. */
    fun checkKey(
        key: String,
        secret: String,
    ): ExchangeKeyInfo {
        val transport = transport({ key to secret }, {})
        return provider.checkKey { transport.get(KEY_CHECK_ACCOUNT, it) }
    }
}

/** Every supported exchange bound to its transport, for the desktop and Android hosts. */
object JvmExchanges {
    val all: List<ExchangeBinding<*>> =
        listOf(
            ExchangeBinding(BybitProvider) { credentials, before -> BybitTransport(credentials, before) },
            ExchangeBinding(BinanceProvider) { credentials, before -> BinanceTransport(credentials, before) },
            ExchangeBinding(MexcProvider) { credentials, before -> MexcTransport(credentials, before) },
        )

    fun of(kind: ExchangeKind): ExchangeBinding<*> = all.single { it.provider.kind == kind }
}
