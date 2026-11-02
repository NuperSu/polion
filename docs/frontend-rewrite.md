# Frontend and refresh behavior

The production default renders the shared `ModelShell` and typed shared screen components in `sharedUi`. `ModelWorkspace` adapts saved application data to each screen's typed state and sends actions to application workflows. The `uiModel` host supplies illustrative fixture data to the same presentation components. Dark, Light and System appearances are available.

## Destinations and action map

| Destination | Content and actions |
| --- | --- |
| Main | Saved market prices and persisted favorites are bound. Global discovery, trending and rankings are not supplied to this screen. Portfolio totals are shown on Portfolio. |
| Portfolio | Portfolio selection, priced balance, saved historical-value chart/ranges, and holdings are bound. Current saved hide-empty, hide-small and sort preferences apply to holdings; the visible Sort / filter controls open Advanced tools. Quote/currency changes are in contextual Portfolio tools. Value change and gain columns are unavailable here. The floating Add action opens a full-screen shared form. |
| Coin detail | Owned quantity, market value and saved quote are bound. Cost basis/gain metrics, transaction summaries, price-chart observations, and coin-level transaction search/type filters are unavailable in this screen. The Back action returns to the previous screen. Transactions link to the contextual activity workflow, where ledger search and filters remain available. Alerts and Order book are unavailable in production. Order-book rows use compact alternating bands when supplied by a host. |
| Insights | Portfolio and priced balance are bound. History series, performance, allocation, gains and other analytics are shown as unavailable until supported data is supplied. Reporting and accounting tools remain in Advanced tools. |
| Menu → Accounts | The shared screen lists saved accounts. Account ownership, public addresses, descriptor/xpub wallets, discovery/scan details, UTXO comparison, archive/restore and wallet-history refresh are available through contextual account tools. |
| Menu → Settings | Automatic refresh and network preference are bound. Appearance, currency, price sources and consent, privacy lock, backup/restore, erasure, rule management and setup remain in contextual settings workflows. |
| Menu → Advanced tools | Opens the full activity, account, portfolio, reporting and settings workflows, including saved transaction records, search/filters, provenance, corrections and accounting tools. These workflows remain the source for records and cost-basis review. |
| Portfolio + | Opens the full-screen shared Add flow. Supported wallet and manual-entry actions hand off to their existing workflows; unsupported exchange and import choices are disabled and muted. |

Compact layouts use three bottom destinations; desktop uses the same sidebar destinations. Coin detail and auxiliary screens return via Back; workflow forms use Close. Presenter state saves portfolio, screen, activity filters, period and the selected coin identity across recreation. Coin tabs and chart range use saveable state; activity search and filters are handled in Advanced tools.

Manual entry can create a local account without a provider. It supports deposits, payments and rewards, with exact asset quantity, optional same-asset fee, UTC timestamp and label. Deposits/rewards require positive quantities; payments require negative quantities. Unsupported trades and account-to-account transfers cannot submit through this form. Fees are separate fee movements. Accounting treatments remain available in Advanced tools. Asset, account, classification and entered fields survive recreation.

The inline alert editor is fixture-only and previews local threshold state. Production price alerts are unavailable; delivery notifications are planned for Phase 7.

## Refresh contract

Pulling down or pressing the header refresh action requests **prices only**, using `refreshAutomaticPrices`. Coin detail delegates to the same coordinator. Missing consent opens the existing price setup flow. The current provider scope/consent and persisted cooldowns govern requests. If another configured supported portfolio currency is outside the allowed scope, refresh opens the existing setup flow for explicit currency-scope consent. There is no incremental wallet fetching claim. Wallet history remains an explicit Accounts/Source settings action; confirmed full recovery remains separate.

The UI observes `priceRefreshStatus` to distinguish queued and running work. A queued callback is not completion. Saved projections remain visible while host-owned workers run. Results are summarized once when work reaches idle, with persisted status available from Menu → Refresh details. Repeated requests are suppressed while queued or running.

Automatic refresh and Wi-Fi-only preferences live in existing portable application settings, with no database schema change. An absent automatic preference is initialized from existing enabled history scheduling or price consent. Startup and backup restore reconcile the two schedules with consent. Disabling automatic refresh cancels periodic wallet and price jobs. Cadences are 30 minutes for wallet history and hourly for prices, subject to host scheduling. Android background jobs use `UNMETERED` when requested; manual price work uses `CONNECTED`. Desktop stores the preference for portability and explains that its restriction applies to Android.

## Validation

Executed checks and remaining device limitations are recorded in [validation](validation.md). This frontend work does not close the open financial, chain-verification or release gates in [Milestone D](milestone-d.md).
