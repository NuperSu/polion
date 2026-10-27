# Rotki reference audit

Local source: `../rotki`, commit `42adc4623b8a2821d70e984d18a14d59bcc656fd`. Public upstream develop resolved on 2026-10-28 to `87fbd063936fe41656f2b8816cf474d638f703e4`. The observations below concern the local commit; upstream source equivalence is not asserted.

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
