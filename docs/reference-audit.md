# Rotki reference audit

Local source: `../rotki`, commit `42adc4623b8a2821d70e984d18a14d59bcc656fd`. Public upstream develop resolved on 2026-10-28 to `87fbd063936fe41656f2b8816cf474d638f703e4`. Selected upstream files were fetched at that pinned commit and compared with the local checkout on 2026-10-28.

## Source map and observations

- `rotkehlchen/chain/bitcoin/manager.py` coordinates provider retrieval, database access and Bitcoin event decoding. Polion separates ingestion, decoding and projection responsibilities.
- `rotkehlchen/chain/bitcoin/xpub.py` and Bitcoin/xpub tests illustrate grouped address discovery and derivation cases. Ownership and duplicate tracking need explicit identities.
- `rotkehlchen/history/events/structures/base.py` connects event direction with accounting classification. Polion preserves provenance and user overlays independently of classifications.
- `rotkehlchen/db/schema.py` contains distinct asset, blockchain account, xpub and historical balance records.
- `rotkehlchen/accounting/accountant.py` processes events alongside missing-price handling and report persistence. Missing prices must remain visible.
- `rotkehlchen/tests/unit/test_bitcoin.py`, `test_bitcoin_transactions.py`, and `tests/db/test_xpubs.py` provide scenario references for validation, derivation and transactions. No fixtures have been copied.

## Licensing

Rotki distributes GNU AGPL version 3 in `LICENSE.md`. Polion uses AGPL-3.0 by project choice. The license text is copied verbatim; application source and test fixtures are independently written. Future source reuse requires preservation of applicable notices and explicit attribution.

## Risks

Provider privacy, duplicate ownership, chain reorganizations, absent acquisition costs, and cross-platform persistence and encryption require later implementations and tests. Reference inspection does not establish accounting correctness. The adjacent checkout remains independent.

## Selected upstream comparison

All three files differ byte-for-byte between the local checkout and the pinned upstream revision:

- Bitcoin `manager.py`: upstream wraps transaction querying in `try`/`finally` so the finished progress status is sent on failure; it also changes the diagnostic log formatting. Local SHA-256 `14d1a475d3789ee0b1b3c29e2a1dd46b1b5e26dc1c04c767b7b51a2d7210b8b7`; upstream `4f35fbd92d972bd5b29618e28d8ea59317c52cac22626755f5938bf978493a36`.
- History event `base.py`: upstream includes a bank transaction event type and separates regenerated automatic notes from persisted user notes. Local SHA-256 `6a830332215cc50c4d39733c8c46b1d7f57ffa50ce4548bdd22b1857c01c1844`; upstream `875ea20fab9efd3083c939d018e8560bc05c86911d49c4741fa7d3299546a3d8`.
- `accountant.py`: upstream adds structured classifications to user messages for missing prices, network failures and unknown assets. Local SHA-256 `cd04aca0d33c68d158a9003ab1f29cddb563ca67c7344272b8761589a5688b63`; upstream `3d69ef91a326e397d40083ebbedac22be00c7e74b0490589a11d04b8e43ef2ca`.

These are selected observations, not exhaustive diffs. They reinforce the need to treat failure status, generated data and user edits, and missing data as explicit concerns.
