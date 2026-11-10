# Valroot UI model

A deterministic Compose fixture host for the shared production presentation layer. It supplies illustrative crypto-only data and preview actions to the same typed renderers used by the application.

## Navigation and preview interactions

- **Main:** top coins, favorites, trending, top gainers, and top losers. No portfolio summary or news.
- **Portfolio:** portfolio selector, balance, chart, holdings, and floating wallet/add controls. Coin rows open details.
- **Insights:** portfolio history, performance, diversity, gains, locations, and fees.
- **Coin details:** cost/gains breakdown and General, Transactions, Alerts, and Order book tabs. No analysis/discussion tab or paid badges.
- **Add:** the Portfolio + opens method selection (wallet, exchange, manual entry and CSV). Manual entry opens a buy/sell/transfer form. New Transaction inside a coin opens the form directly.
- **Menu:** Accounts and Settings. Settings demonstrate Automatic refresh and Wi-Fi-only background refresh.

All financial figures, charts, transactions, alerts and order-book rows are fixed samples. Navigation, scrolling, tabs, breakdown visibility, text fields, switches and range-chip selection work locally for reviewing the design. The form's Done action closes the preview without saving. Disabled connection and import choices are shown in gray. The alert editor is inline; system notification delivery belongs to a later phase.

Pull down at the top of a page or use the header refresh button to simulate refresh progress. No database or provider requests occur. The sample text about incremental refresh is illustrative and is not a claim about production synchronization.

## Run

From the repository root with JDK 25:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :uiModel:run
```

The window opens on Portfolio. Resize to review phone and desktop layouts; at 850 dp navigation moves to a sidebar. Long pages scroll inside the viewport.

## Render and verify

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk SKIKO_RENDER_API=SOFTWARE \
  xvfb-run -a ./gradlew :uiModel:test
```

PNG previews are generated in `uiModel/build/previews/` at **390×844**, **360×800**, and **1440×900**. Lower sections have separate `scrolled` captures at the same viewport sizes. The harness removes obsolete PNGs from that output directory when regenerating previews. It checks navigation, detail tabs, transaction form and settings. Use `:uiModel:ktlintCheck` for formatting checks.

The fixture depends on `sharedUi`, which transitively depends on `core`, but does not read a real ledger or call a provider. Coin marks and charts are drawn in Compose. Production adapters supply actual state and actions to the shared renderers; unsupported data and operations remain unavailable. Visual comparison with the frozen reference remains pending.
