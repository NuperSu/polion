# Holdings projections

HoldingsRepository persists account/asset deltas and a per-activity fingerprint checkpoint. Refresh consumes the complete current activity revision set, reverses changed/deleted contributions, and commits replacement deltas and balances atomically. Force replay discards derived caches and reconstructs them. Confirmed movements contribute to confirmed quantities; pending/reversed/superseded records do not.

Historical queries replay current revisions through an inclusive UTC epoch-millisecond boundary. A reorganization corrects the effective history; this API is not a query for what the application believed at an earlier retrieval time. Wallet hierarchy, owner and portfolio aggregation use sets of selected accounts to avoid overlap. Normalizers must emit a movement once per economic account; address aliases are tracking configuration, not extra ledger accounts.

Balance coverage is supplied explicitly by the application using sync/source coverage. Missing account balances are unknown unless coverage is COMPLETE. Reported observations retain provider and timestamp, and reconciliation requires matching timestamps. Quantities are independent from price availability. Materialized checkpoints remain derived cache and can always be rebuilt from authoritative activities.
