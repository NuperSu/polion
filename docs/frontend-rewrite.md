# Frontend and refresh behavior

The production default renders the shared `ModelShell` and typed shared screen components in `sharedUi`. `ModelWorkspace` adapts saved application data to each screen's typed state and sends actions to application workflows. The `uiModel` host supplies illustrative fixture data to the same presentation components. Dark, Light and System appearances are available.

## Destinations and action map

| Destination | Content and actions |
| --- | --- |
| Main | Saved market prices and persisted favorites are bound. Global discovery, trending and rankings are not supplied to this screen. Portfolio totals are shown on Portfolio. |
| Portfolio | Portfolio and quote selection, priced balance, saved historical value, period-labelled value changes and holdings are bound. In-place controls persist name/value sorting and hide-empty/hide-small preferences. Changes include deposits and withdrawals and are not investment returns. The explanation shows native recorded movements and relevant coverage issues. The floating Add action opens a full-screen shared form. |
| Coin detail | Owned quantity, market value, saved quote, per-unit price history and evidence-dependent FIFO basis/gains are bound. Missing basis remains unknown and uncertain ordering is provisional. Market-only assets have no invented ownership. Scoped transaction cards, quantity-weighted execution averages, search/type/status filters and pagination use effective ledger evidence. The Back action returns to the previous screen. Transaction cards open provenance, corrections, manual editing and confirmed reversal; scoped CSV exports include all matching pages. Alerts and Order book are unavailable in production. Order-book rows use compact alternating bands when supplied by a host. |
| Insights | Portfolio and priced balance are bound. History series, performance, allocation, gains and other analytics are shown as unavailable until supported data is supplied. Reporting and accounting tools remain in Advanced tools. |
| Menu → Accounts | Shared cards show saved source identity, portfolio membership and per-account acceptance. Direct connection, history refresh/retry, holdings, archive/restore, UTXO, rules and reconciliation actions are available. Owner/group/derived-address management and order verification remain contextual. |
| Menu → Settings | Automatic refresh and network preference are bound. Wallet history, provider consent and confirmed recovery open directly. Appearance, currency, prices, privacy lock, backup/restore, erasure and setup remain contextual. |
| Menu → Advanced tools | Opens the full activity, account, portfolio, reporting and settings workflows, including saved transaction records, search/filters, provenance, corrections and accounting tools. These workflows remain the source for records and cost-basis review. |
| Portfolio + | Opens the full-screen shared Add flow. Supported wallet and manual-entry actions hand off to their existing workflows; unsupported exchange and import choices are disabled and muted. |

Compact layouts use three bottom destinations; desktop uses the same sidebar destinations. Coin detail and auxiliary screens return via Back; workflow forms use Close. Presenter state saves portfolio, screen, activity filters, period and the selected coin identity across recreation. Coin tabs, chart range and transaction filters use saveable state. Full activity filters also remain in Advanced tools.

Manual entry supports Buy, Sell, Transfer, Deposit, Withdrawal, Reward and Fee through a validated saved draft and review step. Buy/Sell create both gross asset and consideration legs; fees are separate expense disposals at zero proceeds. Same-owner tracked transfers preserve lot age and basis; external incoming basis and outgoing proceeds remain unknown. A third-asset trade fee requires an explicit evidenced conversion price. Quantities and fees must fit each asset's atomic precision, and consideration cannot be silently rounded. Local accounts can be created without a provider. Drafts and review state survive recreation. Manual edits preserve activity and role-based movement IDs; reversal appends a retained status revision. Imported evidence remains editable only through correction overlays. See [Phase 3 evidence](ui-model-phases/phase-3-evidence.md).

USD display uses two decimals, with nonzero sub-cent amounts bounded as `<$0.01`. Exact stored observations and calculation inputs are retained. See [Phase 2 evidence](ui-model-phases/phase-2-evidence.md) for range, freshness and financial definitions.

The inline alert editor is fixture-only and previews local threshold state. Production price alerts are unavailable; delivery notifications are planned for Phase 7.

## Refresh contract

Pulling down or pressing the header refresh action requests **prices only**, using `refreshAutomaticPrices`. Coin detail delegates to the same coordinator. Missing consent opens the existing price setup flow. The current provider scope/consent and persisted cooldowns govern requests. If another configured supported portfolio currency is outside the allowed scope, refresh opens the existing setup flow for explicit currency-scope consent. There is no incremental wallet fetching claim. Wallet history remains an explicit Accounts/Source settings action; confirmed full recovery remains separate.

The UI observes `priceRefreshStatus` to distinguish queued and running work. A queued callback is not completion. Saved projections remain visible while host-owned workers run. Results are summarized once when work reaches idle, with persisted status available from Menu → Refresh details. Repeated requests are suppressed while queued or running.

Automatic refresh and Wi-Fi-only preferences live in existing portable application settings, with no database schema change. An absent automatic preference is initialized from existing enabled history scheduling or price consent. Startup and backup restore reconcile the two schedules with consent. Disabling automatic refresh cancels periodic wallet and price jobs. Cadences are 30 minutes for wallet history and hourly for prices, subject to host scheduling. Android background jobs use `UNMETERED` when requested; manual price work uses `CONNECTED`. Desktop stores the preference for portability and explains that its restriction applies to Android.

## Validation

Executed checks and remaining device limitations are recorded in [validation](validation.md). This frontend work does not close the open financial, chain-verification or release gates in [Milestone D](milestone-d.md).

Account/source identity and acceptance definitions, public fixture provenance and scheduling checks are recorded in [Phase 4 evidence](ui-model-phases/phase-4-evidence.md).
