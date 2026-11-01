# Phase 5 — Make Main a real market-discovery screen

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 4 — Finish Accounts, history refresh and recovery](4-finish-accounts-history-refresh-and-recovery.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Add a provider capability layer for asset catalog, stable asset mappings, current quotes, historical candles, market ranking, trending and comparable-period changes. Evaluate provider terms, attribution, quotas, privacy, credentials and operating cost before selecting services. Asset symbols alone cannot establish identity.

Populate the model's Top Coins cards and sparklines, Favorites rows, Trending cards, Top Gainers and Top Losers. Define the ranked universe, observation time, quote currency and measurement window. A local subset must not be described as a global ranking. Market browsing must work without connecting a wallet.

Persist favorites, cache each feed independently and support offline browsing. Coordinate foreground refresh, cooldowns, retries and partial failures without duplicate calls or discarding saved data. Make chart/change windows consistent across Main and coin details.

**Exit:** every Main section has a real feed, correct ranking/change semantics, working coin navigation and tested empty/offline/rate-limited states. Permanent placeholder blocks do not satisfy this phase. Adding market coins does not enable unsupported wallet connectors.
