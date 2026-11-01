# Phase 7 — Finish alerts, order books and CSV import

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 6 — Implement Insights in the model's composition](6-implement-insights-in-the-model-s-composition.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Alerts need a persisted rule model, price source, threshold/direction, baseline/reset semantics, cooldown/deduplication, enable/edit/delete flows and delivery history. Implement desktop evaluation while open and Android notification permission/background scheduling. Explain operating-system timing limits; do not promise continuous monitoring when the app cannot provide it.

Order books need a selected venue and trading pair, supported depth, initial snapshot, streaming/polling updates, sequence/gap handling, stale/disconnected states and lifecycle cleanup. Do not label one venue's book as a global order book. Keep the modeled bid/ask columns and depth presentation, with accessible exact values.

CSV import needs supported templates, mapping, preview, row-level validation, duplicate detection, explicit account/asset/time-zone/fee treatment, provenance and atomic commit. Preserve an import receipt and safe correction/reversal path. Never silently guess trades or token identities.

**Exit:** the model's controls save and operate real features; alerts do not duplicate across restarts; books recover from feed gaps; repeated imports do not duplicate ledger records. Unavailable states remain appropriate for unsupported providers, but not as substitutes for these implementations.
