# Activity ledger

Activities contain signed, exact, multi-asset movements with account identities and explicit fee/trade/reward roles. Source-derived records carry source links and decoder versions; manual records have separate origin. UTC epoch milliseconds and an explicit stable ordering key retain temporal ordering context.

SQLite persistence appends complete activity revisions transactionally. Replaying an identical latest activity is idempotent. Reclassification, reversal and supersession preserve earlier revisions. The origin cannot change from manual to source-derived or vice versa. Sources remain linked rather than becoming ledger identities.

LedgerDeltas computes account/asset signed deltas from confirmed activities, optionally including pending activities. Reversed and superseded activities contribute nothing. Duplicate activity IDs are rejected to prevent accidental double counting. This is a ledger validation utility; historical portfolio projections and accounting are later phases.

Tests exercise deposits, payments and fees, internal transfers, multi-asset trades, rewards, pending/reversed states, exact amounts, revision replay and database reopen. No source-provider calls are made.
