# EVM provider migration: Blockscout to Alchemy Portfolio APIs

**Status:** Implemented 2026-11-08 (see docs/evm.md).

## Context

L2 shipped on branch `l2-evm` with Blockscout as the EVM provider. Live testing with Anton's key showed that Blockscout's free tier misses the chains that matter:
- Base, Polygon and zkSync need a paid plan.
- BNB Chain and Avalanche aren't offered at all.

Anton's test wallet (`0xDBAb…1DF9`) holds assets on BNB. Routescan and Etherscan free tiers don't cover Base, BNB or Polygon either. Anton decided to move to Alchemy's Portfolio APIs.

Facts checked in Alchemy's docs (2026-11-08):
- **Transfers By Wallet (beta):**
  - **Request:** `POST https://api.g.alchemy.com/data/v1/{key}/transfers/by-address`. One address and up to 5 networks per request. Categories: `external`, `internal`, `erc20`, `erc721`, `erc1155`, `specialnft`. Results are merged across networks, ordered by `blockTimestamp`, and paged with an opaque `pageKey`; `maxCount` defaults to 100.
  - **Networks (16):** Ethereum, Polygon, Arbitrum, OP, Base, BNB, Celo, Ronin, Monad, Robinhood, Arc and Gnosis (plus testnets).
  - **Internal transfers** only on Ethereum, Base, Polygon, Arc and Robinhood.
  - **Per transfer:** `hash`, `from`, `to`, `blockNum` (hex), `blockTimestamp`, `uniqueId`, `contractAddress`, `fungible.rawValue`/`decimals`/`asset`, `nfts.tokens[].tokenId`/`amount`.
  - **Not returned:** gas fees, transaction status, and staking withdrawals.
  - **Partial failures** come back as HTTP 200 with `error.partialErrors[]` per network (`unsupported`, `timeout` or `error`).
- **Tokens By Wallet:** `POST …/assets/tokens/by-address`, up to 2 addresses × 5 networks. Returns native (`tokenAddress: null`) and ERC-20 balances with decimals, symbol, name, logo and USD price, on 30+ EVM chains.
- **Fees and status need receipts** from Alchemy's per-network JSON-RPC (`https://{network}.g.alchemy.com/v2/{key}`). The same key works there.

## What stays

These pieces are provider-neutral and stay as they are:
- `EvmIdentity.kt`: addresses, EIP-55, `eip155:<chain>` asset IDs, `evmQuantity`.
- The decoder model (`EvmTransaction`, `EvmInternalTransfer`, `EvmTokenTransfer`) and `EvmDecoder` grouping.
- CoinGecko coin links, hidden assets and linked-coin prices.
- The `evm_chains` state and `EvmService.applyChainHistory` (raw provenance, supersede, one transaction).
- `EvmService.checkBalances` and the connector shape (`SourceConnector`, head check, balance check).
- The UI: connect flow, chain status, hidden review, coin aggregation.

## Changes

### 1. Alchemy client (`core/…/evm/Alchemy.kt`)
- **Network table:** each Alchemy slug with its chain ID, for the 16 transfer networks. Chain IDs are checked once per network with `eth_chainId` and cached; an unknown slug is never guessed.
- **Requests and parsers, strict like the current ones:**
  - transfers (body: `address`, ≤5 `networks`, all categories the network supports, `order: desc`, `maxCount: 1000`, `pageKey`);
  - tokens by wallet (balances for the check, native included);
  - JSON-RPC batches of `eth_getTransactionReceipt` (≤50 per batch) and `eth_getTransactionByHash` when a receipt lacks the gas price;
  - `eth_getTransactionCount`.
- **Mapping into the existing model:**
  - `external` becomes `EvmTransaction`, with value from `rawValue`, and with fee and status from the receipt;
  - `internal` becomes `EvmInternalTransfer`;
  - `erc20`/`erc721`/`erc1155` become `EvmTokenTransfer`, with decimals from `fungible.decimals` or the token's metadata.
- **Fee from the receipt:** `gasUsed × effectiveGasPrice`, plus `l1Fee` when present (OP-stack chains such as Base and OP). Only transactions the address sent pay a fee.
- **Partial errors:** `unsupported` marks that category as not covered on that chain; `timeout`/`error` fail that chain's refresh so nothing partial is applied.

### 2. Gaps the API leaves, made visible rather than guessed
- **Failed transactions:** Alchemy lists value transfers, so a failed transaction may not appear and its gas would be missing. After reading history, the connector compares `eth_getTransactionCount` (the nonce) with the number of distinct sent hashes. A gap is stored as "N sent transactions not listed (likely failed); their gas is not counted" and shown on the account.
- **Contract-made (internal) transfers on BNB, Arbitrum, OP and other chains without the internal category:** not available. The chain is marked "contract payments not covered".
- **Staking withdrawals (Ethereum, Gnosis):** not available from Alchemy. The balance check reports the difference.
- **Balance check:** native and token balances from Tokens By Wallet are compared with the ledger after each full read (`EvmService.checkBalances`). Any gap above shows up as a difference instead of a silently wrong total.

### 3. Discovery and refresh (`EvmConnector`)
- **Discovery:** one Tokens By Wallet request per 5 networks (2 addresses per request) over the 16 networks. Chains with a balance or with any transfer (transfers request with `maxCount: 1` per 5-network group) are added. This replaces the Blockscout multichain search.
- **Head check per chain:** a transfers request for that network with `maxCount: 1`; its newest `uniqueId` plus the nonce form the head. Unchanged means skip, at two small requests per chain.
- **Full read** when the head changed: all pages, then receipts for sent transactions, then apply, then the balance check. "Recover full history" forces it.

### 4. Key, consent, hosts and UI
- **Key:** the Alchemy key replaces the Blockscout key as a device setting (`alchemy_api_key`) in Settings › API Keys › External services. Help text: "Free key from alchemy.com. Reads the history of EVM addresses on Ethereum, Base, BNB Chain, Polygon, Arbitrum, OP and other chains." Remove the Blockscout key row and setting.
- **Consent text:** "Alchemy receives this address and your IP address."
- **Transport:** `AlchemyTransport` (`core/src/jvmSharedMain/kotlin/io/valroot/core/evm/AlchemyTransport.kt`), shared by Android and desktop:
  - HTTPS only, to `api.g.alchemy.com/data/v1/{key}/(transfers|assets/tokens)/by-address` and `{slug}.g.alchemy.com/v2/{key}` (slug from the network table);
  - POST with a JSON body, the key inserted into the path by the transport;
  - the key and full URLs never logged;
  - 429 handling, a 16 MiB cap, and pacing at a conservative 5 requests per second;
  - registered in the same `SourceRegistry` places.
- **UI:** the chain picker lists the 16 supported networks, named from the CoinGecko catalog. Per-chain status shows the coverage notes from step 2.

### 5. Removal
The Blockscout client, transport, tests, fixtures and key setting are removed from the code. Keep `EvmWithdrawal` and its decoder support (dormant until a provider supplies withdrawals). Update `docs/evm.md`, `release.md` and `plan-2026-11.md`.

## Build split

- **Me:** the Alchemy client and parsers, receipts and fees, the nonce gap check, connector discovery and head logic, core tests, and fixtures.
- **Haiku xhigh agents** (own worktrees from the current commit):
  1. Hosts: `AlchemyTransport`, key storage, registry, removal of the Blockscout transport.
  2. UI: key row, chain picker list, coverage notes, removal of the Blockscout row.
- **First step:** Anton saves an Alchemy key to `~/.config/valroot-dev/alchemy.key` (read by curl from the file, never printed). Capture real responses for `0xDBAb…1DF9` on BNB and for a public address with Base/OP history, and confirm the response shapes, the `maxCount` limit and the receipt `l1Fee` field before writing the parsers.

## Verification

- **Independent fixtures:** real Alchemy captures cross-checked against public RPC nodes (receipt `status`, `gasUsed`, `effectiveGasPrice`, `l1Fee`). They cover:
  - BNB native and BEP-20 transfers;
  - Base ETH with an L1 fee;
  - USDC (6 decimals);
  - an NFT;
  - an internal transfer on Ethereum;
  - a uint256 amount.
- **Core tests:**
  - parsers, including partial-error handling;
  - fee from the receipt, including the L1 fee;
  - the nonce gap reported;
  - coverage notes;
  - head skip;
  - supersede;
  - a balance-check difference;
  - discovery across network groups;
  - existing EVM tests adapted.
- **Live check on desktop:** track `0xDBAb…1DF9`; BNB holdings appear, priced, and match Alchemy's balances or show the documented gaps.
- **Full CI task set locally,** including DejaVu fonts. Emulator smoke test of the release APK.
- **Docs:** `docs/evm.md` rewritten for Alchemy, including the coverage gaps. The plan copy is saved as `docs/evm-alchemy-migration.md`.

## Out of scope

DeFi decoding and bridge linking (L3). Withdrawals until a provider supplies them. A second provider for chains Alchemy doesn't list.
