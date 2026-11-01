# Polion UI model

Standalone Kotlin Compose model inspired by the supplied reference screenshots. Black backgrounds, charcoal cards, outline icons, and crypto-only illustrative content.

## Navigation and preview interactions

- **Main:** top coins, favorites, trending, top gainers, and top losers. No portfolio summary or news.
- **Portfolio:** portfolio selector, balance, chart, holdings, and floating wallet/add controls. Coin rows open details.
- **Insights:** portfolio history, performance, diversity, gains, locations, and fees.
- **Coin details:** cost/gains breakdown and General, Transactions, Alerts, and Order book tabs. No analysis/discussion tab or paid badges.
- **Add:** the Portfolio + opens method selection (wallet, manual entry, CSV placeholders). Manual entry opens a buy/sell/transfer form. New Transaction inside a coin opens the form directly.
- **Menu:** Accounts and Settings. Settings demonstrate Automatic refresh and Wi-Fi-only background refresh.

All financial figures, charts, transactions, alerts and order-book rows are fixed samples. Navigation, scrolling, tabs, breakdown visibility, text fields, switches and range-chip selection work locally for reviewing the design. The form's Done action closes the preview without saving. Connection/import controls are placeholders.

Pull down at the top of a page or use the header refresh button to simulate refresh progress. No database or provider requests occur. The intended future backend behavior is incremental refresh across all portfolios with a small overlap; that behavior is not implemented by this design module.

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

This module is independent of the financial core and application hosts. It uses no real wallet data or network integration. Coin marks and charts are drawn in Compose. Brokerage/eToro sections and stock/fund/index content are excluded.
