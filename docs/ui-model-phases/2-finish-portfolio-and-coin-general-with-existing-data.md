# Phase 2 — Finish Portfolio and coin General with existing data

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 1 — Build one faithful presentation layer](1-build-one-faithful-presentation-layer.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Bind portfolio/quote selection, balance, changes, history and holdings to the existing ledger, holdings and price projections. Reproduce the model's headline/chart/toolbar/row layout. Sorting and range controls must change the displayed data. Preserve saved results during refresh and failure.

Provide an actual unit-price chart independently of ownership. Define historical quote selection, timestamp alignment, freshness and gaps. Show observed prices and their source; a single exchange feed must not be labeled a global average. Implement supported intraday and long-range controls; track missing resolution/history as backend work rather than silently mapping several chips to the same series.

Bind the six coin metrics to scoped evidence. Do not invent basis for transfers or opening balances. Distinguish position value, unrealized gain, realized gain and total gains. A market-only coin has market data and no invented ownership or gains. Keep exact values available through detail, including prices below one cent.

The Portfolio explanation action should open a concise factual summary of changes, contributions and relevant data issues. It does not require an AI feature.

**Exit:** balances, quantities and supported gains reconcile with the existing engines; changes have explicit periods and meanings; chart controls work; selecting/sorting holdings and navigating between portfolio and coin details preserves scope. Visual parity passes using real projected fixture data.
