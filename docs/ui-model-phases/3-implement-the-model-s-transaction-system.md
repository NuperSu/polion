# Phase 3 — Implement the model's transaction system

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 2 — Finish Portfolio and coin General with existing data](2-finish-portfolio-and-coin-general-with-existing-data.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Create a draft/validation layer for Buy, Sell, Transfer, Deposit, Withdrawal, Reward and Fee, exposing only supported combinations. Buy/Sell must produce linked asset and consideration movements, with explicit account, pair, executed quantity, unit price, timestamp, fees and notes. A trade cannot be saved as a generic single-leg transfer.

Transfers need source/destination accounts, correct asset identity, separate fees, and a basis-continuity policy. Transfers involving an external account must preserve uncertainty rather than invent acquisition costs. Support fees in the traded asset, quote asset or another supported asset only when the necessary conversion/evidence exists. Save the ledger record and applicable accounting treatment atomically.

Reproduce the model's form composition with sensible defaults, coin preselection and account creation/selection. Preserve drafts across recreation. Add review, edit/correction and reversal flows that preserve imported evidence and stable IDs.

Build the modeled transaction timeline and cards: type/date grouping, From, To, Amount, Fee, Worth Now and Worth Then, plus average buy/sell price and transaction count. Missing counterparties, historic quotes or purchase evidence remain unknown. Define quantity-weighted averages and fee treatment explicitly. Provide filters, pagination, correction, provenance and scoped export through contextual actions.

**Exit:** independent examples for buy, sell, internal/external transfer, reward, withdrawal and multi-asset fees yield exact movements, balances and accounting results. Invalid combinations cannot submit; saved/reopened/restored records match; timeline fields trace to their evidence. The same workflows pass on Android and desktop.

Implementation definitions and platform evidence are tracked in [Phase 3 evidence](phase-3-evidence.md). Final visual review is user-owned.
