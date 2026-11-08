# EVM addresses (Ethereum and other chains)

Code: `core/…/evm/` (identity, Blockscout parsing, decoder, connector, chain state), `core/…/market/CoingeckoCatalog.kt` and `AssetLinkRepository.kt`, `PortfolioApplication.applyEvmChainHistory` / `checkEvmBalances` / `refreshEvmCatalog` / `refreshLinkedCoinPrices`. Tests: `EvmIdentityTest`, `BlockscoutTest`, `EvmConnectorTest`, `EvmAssetsApplicationTest`, `CoingeckoCatalogTest`.

## Tracking an address

An EVM address is tracked once and covers every chain chosen for it. It is validated as `0x` plus 40 hexadecimal characters; a mixed-case address must carry a correct EIP-55 checksum, so a mistyped character is caught. It is stored in lower case.

## Provider

History comes from Blockscout's API (`api.blockscout.com/{chainId}/api/v2`) with the user's free key from dev.blockscout.com (5 requests per second, 100,000 credits a day). The key stays on the device and is not part of backups. Requests need the EVM consent; Blockscout receives the address and the device's IP address. The host paces requests at 4 per second.

## What a refresh reads

Per chain:

- **Skip check:** the newest transaction, internal transfer and token transfer, plus the newest staking withdrawal on Ethereum and Gnosis. If all equal the saved head, nothing else is read: three or four small requests per chain. Blockscout's counters are not used, because they can be stale.
- **Otherwise the complete history:** transactions, internal transfers, token transfers and withdrawals, all pages. It is applied in one database transaction, and the balances are then checked.
- **Full history recovery** skips the head check.
- **What the skip check misses:** a change deeper in history while the newest items stay the same. The balance check and full recovery cover that case.

## Decoding

- **One activity per transaction and tracked address**, with net movements per asset. A transfer between two tracked addresses is therefore two activities, which can be linked like any transfer.
- **Gas:** the fee is its own movement whenever the address sent the transaction. Blockscout's fee value is the total paid, including the L1 data fee on rollups. A failed transaction moves no value but still costs its fee.
- **Internal transfers:** native coin moved by contracts. Failed ones are ignored.
- **Tokens:** ERC-20 amounts use the token's decimals. ERC-721 and ERC-1155 tokens are one asset per contract and token ID.
- **Staking withdrawals** on Ethereum and Gnosis are incoming native coin with no fee. Whether a withdrawal is principal or reward is not assumed.
- **Pending and removed transactions:** a pending transaction is recorded as pending. One that the provider stops reporting is superseded, not deleted.
- **No accounting treatment is assumed**, as for Bitcoin.

## Assets and coins

- **Assets are exact per chain:** `eip155:<chain>/native`, `eip155:<chain>/erc20:<contract>`, `eip155:<chain>/nft:<contract>:<tokenId>`.
- **Links to CoinGecko coins:** CoinGecko's keyless catalog (`/asset_platforms` and `/coins/list?include_platform=true`) links them, so ETH on Ethereum, Base and Arbitrum are one coin with a per-chain breakdown.
  - The catalog is cached on the device for a week.
  - A contract listed under two coins on one chain is never linked.
  - Native coins take their symbol and name from CoinGecko; a chain CoinGecko does not know shows "Native coin (chain N)".

## Hidden tokens

- **Hidden by default:** tokens without a CoinGecko coin and all NFTs, because unsolicited tokens are often scams.
- **Effect:** hidden assets stay in the ledger and in accounting but are left out of values and totals, and listed for review.
- **Choice:** showing or hiding an asset is the user's choice and is kept in backups.

## Prices

Linked coins are priced from CoinGecko's keyless `/simple/price`, in batches of 50, in the display and secondary currencies. One price is recorded for every asset linked to the coin. NFTs and unlinked tokens have no value.

## Balance check

After each full read, the ledger's native balance and every token balance Blockscout lists are compared with Blockscout's figures. A difference is stored per chain and shown on the account; it points to missing history or an unindexed transfer.

## Limits

- **Not decoded:** DeFi positions, liquidity tokens and bridges (L3). Cross-chain bridge transfers are not linked automatically.
- **Size:** histories longer than 400 pages per list are refused rather than truncated.
- **Credits:** Blockscout credits limit how many addresses and chains can refresh often. An unchanged chain costs about 90 credits per refresh.
