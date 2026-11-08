# Incremental Bitcoin history project

Status: design only, with one narrow path live. Ordinary wallet refresh first compares each active address's chain and mempool counts with the last applied scan. When the fingerprint, anchor and every count match and no mempool activity was recorded, it fetches no history and applies nothing. Any other case runs the full scan. No partial or newest-pages-only fetching is enabled by this document. The live skip relies on the provider's counters and the canonical accepted anchor; it does not verify older history independently.

## Provider capability boundary

The [Esplora API](https://github.com/Blockstream/esplora/blob/master/API.md#addresses) offers address chain/mempool counters, newest-first confirmed pages addressed by a last-seen transaction ID, a limited unpaged mempool response, transaction status and UTXOs. It does not document an address change sequence or a durable incremental subscription cursor. Counters and a pagination transaction ID cannot prove that older records are unchanged. The implementation must detect truncated mempool responses and treat ambiguous provider evidence conservatively.

## Proposed acceptance protocol

1. Persist the accepted endpoint identity, canonical address-to-account scope, chain anchor, known confirmed boundary and last observed mempool set. New addresses, reassignment, changed endpoint, restored incompatible state or source-scope changes invalidate reuse. Discovery retains its gap assumptions separately.
2. Check the accepted anchor against the current provider chain before reusing confirmed records. Locate a verified common ancestor within the supported reorg bound. Fetch an overlapping window that includes all observations after that ancestor plus a configurable safety depth. A match on transaction ID alone is insufficient: block membership, confirmation state and retained checksums must agree.
3. Reconcile the complete current mempool set against the previous one. Re-query known pending transactions and relevant spend status when something disappears. A disappearance is not a reversal or proof of replacement. RBF and conflicting descendants require explicit spending relationships and confirmation evidence; ambiguous or saturated responses require a full scan or leave accepted state unchanged.
4. Page confirmed history from the head until the overlap boundary is established for every active address. Validate address counts, transaction identity, graph consistency and UTXOs. Perform a final tip/anchor/scope/consent check. Pagination state is scratch progress, not an acceptance checkpoint.
5. Commit the staged ledger, graph membership, pending/conflict changes and accepted anchor atomically. An interrupted, inconsistent, offline or rate-limited fetch retains the prior accepted ledger and its provenance. Restart may reuse validated scratch bodies but must revalidate mutable provider observations.
6. Invalidate cursors on a missing boundary, changed transaction body, lagging provider, unexpected count, endpoint/scope change, deep reorg or inconsistent status. Automatic full-scan fallback must preserve bounded resource/provider budgets. A recovery beyond the accepted continuity policy remains an explicit confirmed operation.

Overlap depth is a policy parameter to establish through evidence, not a completeness guarantee. If the documented API cannot prove safe reuse for a scenario, it must use the full scan path instead.

## Required equivalence gate

Run a candidate incremental scan and a separate full replay against the same independently recorded fixture stream. Compare effective activity/movement IDs, exact balances, pending/reversed/replaced states, fee allocation, graph membership, source provenance and supported accounting results. Cover repeated unchanged refresh, pagination boundaries, old-address reuse, descriptor discovery, mempool eviction/saturation, RBF descendants, shallow/deep reorg, provider lag, 429/offline interruption, checkpoint corruption, archive/restore and encrypted backup restore.

Require rollback and restart equivalence on desktop JDBC and native Android SQLite, plus measured memory/provider budgets on large scopes. Enable the path only after those comparisons and recovery checks pass. The current refresh path remains the oracle and fallback; no delivery date or reduced-fetch guarantee is implied.
