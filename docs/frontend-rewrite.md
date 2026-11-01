# Frontend and refresh behavior

The production interface follows the local `uiModel` visual reference using semantic black/charcoal surfaces in dark mode. Explicit Light and System preferences remain available. Production components use saved application projections; design fixtures are confined to `uiModel`.

## Destinations and action map

| Destination | Content and actions |
| --- | --- |
| Main | Saved unit prices, persisted asset favorites, labeled unavailable global rankings, trending, gainers and losers. No portfolio summary. |
| Portfolio | Portfolio and quote selection, priced value, historical value chart/ranges, sortable/filterable holdings, floating add action. Chart values are distinguished from investment returns. |
| Coin detail | Quantity, saved unit price, evidence-dependent FIFO basis/gains, General saved-price chart, scoped Transactions search/type filters/pagination/details/manual entry. Alerts and Order book disclose unavailable services. |
| Insights | Portfolio scope, historical value, allocation, analytics, FIFO treatments/reports, ordering verification, reconciliation and scoped CSV. Unsupported performance calculations remain unavailable. |
| Menu → Accounts | Owners, groups, portfolios, public addresses, descriptor/xpub wallets, address discovery/scan details, UTXO comparison, archive/restore, explicit wallet-history refresh. |
| Menu → Settings | Appearance, currency, manual/provider prices and consent, automatic refresh and Android background network preference, privacy lock, backup/restore, erasure, rule management and setup. |
| Menu → Advanced tools → Activity | Ledger search, account/asset/type/status/network filters, pagination, manual entry, provenance and corrections. |
| Portfolio + | Supported Bitcoin source methods and real manual entry. CSV import is explicitly unavailable. |

Compact layouts use three bottom destinations; desktop uses the same sidebar destinations. Auxiliary screens have a back action. Coin details and workflow forms have close actions. Presenter state saves portfolio, screen, activity filters, period and the selected coin identity across recreation. Coin tabs, search/type filters and chart range use saveable state.

Manual entry can create a local account without a provider. It supports deposits, payments and rewards, with exact asset quantity, optional same-asset fee, UTC timestamp and label. Deposits/rewards require positive quantities; payments require negative quantities. Unsupported trades and account-to-account transfers cannot submit through this form. Fees are separate fee movements. Accounting treatments remain explicit in Insights. Asset, account, classification and entered fields survive recreation.

## Refresh contract

Pulling down or pressing the header refresh action requests **prices only**, using `refreshAutomaticPrices`. Coin detail delegates to the same coordinator. Missing consent opens the existing price setup flow. The current provider scope/consent and persisted cooldowns govern requests. If another configured supported portfolio currency is outside the allowed scope, refresh opens the existing setup flow for explicit currency-scope consent. There is no incremental wallet fetching claim. Wallet history remains an explicit Accounts/Source settings action; confirmed full recovery remains separate.

The UI observes `priceRefreshStatus` to distinguish queued and running work. A queued callback is not completion. Saved projections remain visible while host-owned workers run. Results are summarized once when work reaches idle, with persisted status available from Menu → Refresh details. Repeated requests are suppressed while queued or running.

Automatic refresh and Wi-Fi-only preferences live in existing portable application settings, with no database schema change. An absent automatic preference is initialized from existing enabled history scheduling or price consent. Startup and backup restore reconcile the two schedules with consent. Disabling automatic refresh cancels periodic wallet and price jobs. Cadences are 30 minutes for wallet history and hourly for prices, subject to host scheduling. Android background jobs use `UNMETERED` when requested; manual price work uses `CONNECTED`. Desktop stores the preference for portability and explains that its restriction applies to Android.

## Validation

Executed checks and remaining device limitations are recorded in [validation](validation.md). This frontend work does not close the open financial, chain-verification or release gates in [Milestone D](milestone-d.md).
