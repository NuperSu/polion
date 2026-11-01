# Phase 6 — Implement Insights in the model's composition

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 5 — Make Main a real market-discovery screen](5-make-main-a-real-market-discovery-screen.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Replace the reused report stack with the exact modeled sections and focused drilldowns:

| Section | Required definition and implementation |
| --- | --- |
| History | Scoped portfolio value series with period selection and visible treatment of gaps. |
| Portfolio Performance | Cash-flow-aware return calculation; start with independently tested TWR, add MWR/XIRR only with convergence/failure rules. Normalize portfolio and benchmark comparisons to the same dates/currency. Provide a defined benchmark before displaying a crypto-market series. |
| Decisions | Define transparent, retrospective comparisons, horizons, fees and assumptions before implementing a judgment. Do not produce unexplained good/bad investment verdicts. Any changed wording/layout must be agreed against the reference. |
| Portfolio Diversity | Allocation by the modeled dimensions with an explicit denominator, unpriced-asset treatment and drilldown to holdings. |
| Gains Reporting | Compact asset/quantity/realized/unrealized table backed by scoped accounting, with unknown/provisional values and detailed lot drilldowns. |
| Most Used Exchanges / Asset Location | Use recorded account/venue metadata and defined activity counts. Do not infer a wallet brand or exchange from an address. |
| Trade Statistics / Fees | Define which events count, cancellation/reversal handling, fee ownership and conversion dates. Exclude double-counted transfers and provide exact native-asset fee detail. |
| Asset Worth | Explicitly choose and label position value versus unit price. A position-value chart must distinguish quantity changes from investment return. |

Keep accounting configuration, corrections and detailed evidence behind these cards. Missing data should affect the relevant result, not become a page of repeated warnings.

**Exit:** all sections have agreed definitions and real projections; independent financial fixtures cover contributions, withdrawals, transfers, fees, missing basis, missing prices and uncertain ordering. Tables/charts reconcile with exported results and reproduce the model's hierarchy.
