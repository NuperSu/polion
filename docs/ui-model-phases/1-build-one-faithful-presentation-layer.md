# Phase 1 — Build one faithful presentation layer

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 0 — Establish the exact contract and honest baseline](0-establish-the-exact-contract-and-honest-baseline.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Extract the model header, navigation, cards, tabs, capsules, coin rows, metric grids, chart frames, sort controls, transaction cards and forms into production components. Replace fixed colors with semantic tokens while preserving the model's default dark appearance, dimensions and typography. Keep explicit Light/System preferences.

Create explicit states for Main, Portfolio, Coin detail, Transactions, Insights, Accounts and Settings. Keep data acquisition outside the renderers. Navigation must preserve portfolio, quote, tab, filters, ranges and drafts across recreation, with predictable back/close behavior and desktop keyboard focus.

Render the shared production components with the same deterministic fixture used by the reference preview. This proves composition before backend availability becomes an excuse to substitute older panels. Integrations then supply the same typed contracts.

**Exit:** side-by-side visual review passes for every modeled screen at the three target sizes and enlarged text. No unexplained structural differences remain. This is a presentation milestone, not a fully functional release.
