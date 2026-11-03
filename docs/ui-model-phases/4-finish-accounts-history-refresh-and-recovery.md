# Phase 4 — Finish Accounts, history refresh and recovery

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 3 — Implement the model's transaction system](3-implement-the-model-s-transaction-system.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Present model-style account cards with real source identity, portfolio membership and last accepted refresh. Connecting a Bitcoin address/descriptor/xpub must lead through validation, consent, discovery, progress and the resulting holdings without forcing users through diagnostic panels.

Keep owner/group management, derived addresses, archive/restore, rules, reconciliation, UTXO review and order verification available in contextual/advanced tools. Complete any underlying reconciliation or accounting relationship gaps before using them to calculate financial results.

Preserve the agreed immediate behavior: pull refreshes prices across configured consented portfolio currencies; wallet history is an explicit action. Queued work is not completion. Automatic refresh, cancellation and Android unmetered-network preferences need restart/restore/device evidence.

Treat incremental wallet history as a separate backend project: define provider-supported change discovery, overlapping fetch windows, mempool reconciliation, reorg/RBF handling, cursor invalidation and full-rescan fallback. Compare incremental results against a full-replay oracle before enabling it. Preserve atomic acceptance and never replace good saved state with a partial failed scan.

**Exit:** connect, refresh, interrupted refresh, retry, archive, restore and confirmed recovery work end to end. Independently sourced Bitcoin fixtures cover financial and chain behavior. Incremental behavior is advertised only after equivalence and recovery tests pass. Large-wallet limits remain explicit until independently resolved.

See [Phase 4 implementation and evidence](phase-4-evidence.md), the [public fixture provenance](../phase4-public-fixture.md), and the separate [incremental history design](../incremental-bitcoin-history.md). Visual approval remains user-owned.
