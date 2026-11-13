# Architecture and inception decisions

Valroot is an AGPL-3.0 local-first, watch-only portfolio tracker. Initial validation targets are Android API 26+ and Linux x86-64 desktop. This repository is independent of the adjacent Rotki checkout.

## Boundaries

Shared core owns domain and application behavior without Compose or platform framework dependencies. It provides exact financial primitives, ownership scopes, SQLite persistence, source provenance, versioned activities, connector and synchronization contracts, historical holdings and prices, explicit FIFO accounting, user overlays and reviewed reconciliation, and portable backups. `PortfolioApplication` coordinates those engines and caches projections. Shared Compose consumes that application service for portfolio workflows; Android and desktop hosts provide storage, networking, background scheduling, file dialogs and privacy-lock integration. The hosts serialize application operations and reload projections after background writes.

Bitcoin mainnet public-address history is fetched from a user-consented Esplora endpoint. Scans validate pagination counts and consistency and retain original transaction provenance before atomically updating the ledger. Block-position enrichment is optional; it does not delay the initial balance sync. Source coverage remains partial: independent chain verification and full financial acceptance remain open. Public descriptors and xpubs use bounded gap discovery with persisted child targets. Bitcoin accounting reports identify uncertain FIFO ordering where timestamps do not establish chain order. The roadmap's full Bitcoin and product acceptance gates remain open; see [Milestone D](milestone-d.md).

## Privacy and threat model

Authoritative user data resides locally. Public explorer requests disclose watched addresses and network metadata to the configured endpoint. The application never ingests seeds or private keys, signs or broadcasts transactions, or requires a cloud account. Local malware, unlocked-device access, backups and diagnostic output can expose financial information. The live SQLite database is plaintext. A privacy lock protects the visible screen, not the underlying database; encrypted portable backups protect exported archives. Android Keystore and Linux Secret Service protect privacy-lock verifiers; no provider credentials are stored. Diagnostic events must not include addresses, payloads, credentials or arbitrary exception text.

## Decisions

Five modules separate platform entry points from shared UI and core; `uiModel` is a fixture host for previewing the shared presentation layer. The Gradle wrapper and dependency versions are pinned; JDK 25 is the development baseline. Linux and Android are initial acceptance targets; other desktop platforms need independent validation. [Validation evidence](validation.md) distinguishes automated checks from device and hosted CI verification.

## Code layout

- **`core/src/commonMain`**: domain and application logic for every platform.
  - `application/PortfolioApplication` is the composition root. It holds `snapshot()`, `reload()` and the feature services, which share one `AppContext` (database, clock, repositories, replayed-ledger cache, settings): `assets`, `linkedCoins`, `evm`, `exchanges`, `bitcoin`, `bitcoinWallets`, `sources`, `analytics`, `accounting`, `prices`, `settings`, `market`, `notifications`, `accounts`, `transactions`, `csv`, `backup`, `demo`. Each service lives in its feature package (`exchange.ExchangeService`, `evm.EvmService`, …). A write calls `invalidate()`, so the ledger is replayed on the next read.
  - Sources implement `SourceConnector` and run through a `SourceRegistry` (`app.sources.refresh`). They write only through their service, never into holdings or accounting.
  - `exchange/` is the layer shared by exchanges: `ExchangeProvider` and the `ExchangeProviders` registry, `ExchangeConnector` (refresh loop, windowed stream reader, request budget), `ExchangeHistory` (decoding saved rows plus an `ExchangeSnapshot`), `TradeSymbols`, `ExchangeLedgerBuilder` and `HmacQuery`. `exchange/bybit`, `exchange/binance` and `exchange/mexc` hold each exchange's client, parsers, connector with its provider, and decoder.
- **`core/src/jvmSharedMain`**: code for both JVM hosts (desktop and Android). It holds the `HttpURLConnection` transports next to their features, platform cryptography, `JvmExchanges` (each provider bound to its transport) and `HostRefresh` (consent gates, connectors, progress text and the summary both hosts show).
- **`core/src/jvmMain`, `androidMain`**: the SQLite driver and other platform-only code.
- **`sharedUi`**: the Compose UI. `PlatformActions` is everything the UI asks of a host.
- **`desktopApp`, `androidApp`**: entry points, storage locations, scheduling, locking and notifications. Neither holds source logic.
- **`uiModel`**: deterministic Compose fixture host (`./gradlew :uiModel:run`) for the shared presentation layer. It renders illustrative crypto-only data and preview actions, and makes no database or provider requests.

No Kotlin file may exceed 800 lines (`checkFileSizes`, run by `ktlintCheck`). Keep files under 500 lines where practical.

### Adding an exchange

1. Add an `ExchangeKind` entry with its identity rule.
2. Add a package `core/…/exchange/<name>/` containing:
   - the client (requests, signing and parsers, raising an `ExchangeApiException` subclass);
   - an `ExchangeConnector` subclass whose `sync` reads streams through `readStream` and ends with `app.exchanges.applyHistory`;
   - an `ExchangeHistory` decoder;
   - an `ExchangeProvider` object, listed in `ExchangeProviders.all`.
3. Add the transport in `core/src/jvmSharedMain/…/exchange/<name>/`, implementing `ExchangeTransport`, and bind it in `JvmExchanges`.
4. Add the connect screen's help text. `exchangeKeyHelp` matches every kind, so the compiler asks for it.
5. Add tests: client parsing against documented samples, the connector against a fake API, and the transport.

### Adding a chain family

1. Add an identity, a client and a decoder in a core package, with a `SourceConnector` that declares an `IntegrationManifest`.
2. Add a service in its feature package, exposed as a property of `PortfolioApplication`, for its settings and history writes.
3. Add the transport in `jvmSharedMain`, and add its connector and consent gate to `HostRefresh`.
4. Add a connect flow in `sharedUi`, offered from `SupportedSourceDialog`.
