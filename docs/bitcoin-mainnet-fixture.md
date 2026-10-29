# Bitcoin mainnet fixture provenance

`core/src/jvmTest/resources/bitcoin-mainnet-608636/` contains a fixed public-chain example used by `BitcoinMainnetEvidenceTest`. The fixture has no relationship to a user's portfolio. The account labels and decision to treat two output addresses as owned accounts are test assumptions; on-chain data does not establish common ownership.

## Independent reference

The spend transaction is the `segwit_transaction` test vector published by the rust-bitcoin contributors in `bitcoin/src/blockdata/transaction.rs`, commit [8aec2406262924220321751deb926b53cc9128d5](https://github.com/rust-bitcoin/rust-bitcoin/blob/8aec2406262924220321751deb926b53cc9128d5/bitcoin/src/blockdata/transaction.rs#L1694), release `bitcoin-0.32.7`. That source declares `SPDX-License-Identifier: CC0-1.0`; see the project's [CC0 license](https://github.com/rust-bitcoin/rust-bitcoin/blob/8aec2406262924220321751deb926b53cc9128d5/LICENSE).

The published vector pins raw bytes, txid `f5864806e3565c34d1b41e716f72609d00b55ea5eac5b924c9719a842ef42206`, wtxid `80b7d8a82d5d5bf92905b06f2014dd699e03837ca172e3a59d51426ebbe3e7f5`, input outpoint, weight 442 and vsize 111. The checked-in spend bytes also matched [mempool.space's raw transaction response](https://mempool.space/api/tx/f5864806e3565c34d1b41e716f72609d00b55ea5eac5b924c9719a842ef42206/hex) when captured on 2026-10-29.

The transaction JSON, parent bytes, block headers and merkle branches are factual public Bitcoin-chain captures from the [Blockstream Esplora API](https://blockstream.info/api/) on 2026-10-29. They are not represented as independently licensed rust-bitcoin source code. No explorer prose or implementation code is copied. Resource names map to these paths:

- `parent.json`, `parent.hex`, `parent.proof.json`: `/tx/7cac3cf9a112cf04901a51d605058615d56ffe6d04b45270e89d1720ea955859`, with `/hex` or `/merkle-proof` appended for the latter files.
- `spend.json`, `spend.hex`, `spend.proof.json`: `/tx/f5864806e3565c34d1b41e716f72609d00b55ea5eac5b924c9719a842ef42206`, with the same suffixes; spend hex is identical across the upstream vector and both explorers.
- `parent.header.hex`: `/block/00000000000000000010a3ec7213c5c4e73f4d56be94a8a2c803503d94ebaa3c/header`.
- `spend.header.hex`: `/block/00000000000000000014171330a4b6f9965ee0c2b5d9fa61ceedadc2de0a02a3/header`.

## Fixed assertions and bounds

At block 608209 (transaction position 678), the parent creates output 1 for 511,884 satoshis. At block 608636 (position 279), the spend consumes that exact outpoint, sends 506,078 satoshis to output 0 and pays 5,806 satoshis in fees. The test independently decodes the captured wire bytes, hashes the stripped transactions and block headers, and folds both merkle branches into the headers' committed roots before checking Polion's graph and SQLite ledger projections. It checks the funded state, the spent state, separate sender/recipient scopes, fee allocation and replay parity.

The fixture represents these two transactions at a fixed historical cutoff. It does not establish current recipient UTXOs, current chain canonicality, full header-chain consensus, historical fiat prices, cost basis, complete wallet discovery, interrupted network sync or whole-history financial acceptance. The synthetic account scope and staged scan calls are test adapters. No network requests occur in the test.
