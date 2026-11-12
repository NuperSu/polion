# EVM addresses (Ethereum and other chains)

Code: `core/…/evm/` (identity, Alchemy client, decoder, connector, chain state), `core/…/market/CoingeckoCatalog.kt` and `AssetLinkRepository.kt`, and in `EvmService` (`app.evm`) and `LinkedCoinService` (`app.linkedCoins`): `applyChainHistory`, `checkBalances`, `refreshCatalog`, `refreshPrices`, `refreshUsdPrices`, `backfillUsdHistory`; `core/…/market/DefiLlamaPrices.kt`, `core/…/evm/OneInch.kt` and `EvmRecoveredRepository.kt`. Hosts: `AlchemyTransport`, `OneInchTransport` and `DefiLlamaTransport` in `androidApp` and `desktopApp`. Tests: `EvmIdentityTest`, `AlchemyTest`, `EvmConnectorTest`, `OneInchTransportTest`, `EvmAssetsApplicationTest`, `CoingeckoCatalogTest`, `AlchemyTransportTest`, `EvmUiTest`.

## Tracking an address

An EVM address is tracked once and covers every supported chain it is used on. It is validated as `0x` plus 40 hexadecimal characters. A mixed-case address must carry a correct EIP-55 checksum, so a mistyped character is caught. It is stored in lower case.

## Provider

History comes from Alchemy, using the user's free key from alchemy.com. In the Alchemy dashboard the networks used must be enabled for the key's app. The key stays on the device, is not part of backups, and is never shown in messages. Requests need the EVM consent; Alchemy receives the address and the device's IP address. The host paces requests at 5 per second and pauses after a rate limit.

- **Transfers By Wallet** lists transfers: native coin, ERC-20, ERC-721 and ERC-1155. Contract-made (internal) transfers are listed on Ethereum, Base and Polygon only.
- **Tokens By Wallet** gives native and ERC-20 balances.
- **Per-network JSON-RPC** gives transaction receipts (fee and status) and the count of transactions an address has sent.

Supported chains: Ethereum, BNB Smart Chain, Base, Polygon, Arbitrum One, OP Mainnet, Gnosis, Celo and Ronin.

### 1inch (optional)

A 1inch key (free from business.1inch.com, Settings › API Keys) adds what Alchemy does not list. It is a device setting like the Alchemy key. 1inch receives the address and the device's IP address. Requests are spaced 1.1 seconds apart, within the free plan's 60 a minute. 1inch's public API terms limit free use to personal use; each user brings their own key.

- **History** (`/history/v2.0/history/{address}/events`, up to 10,000 events per request, paged by time) finds transactions in which a contract paid the address native coin, and lists the tokens 1inch shows for the address.
- **Traces** (`/traces/v1.0/chain/{chain}/block-trace/{block}/tx-hash/{hash}`) give every call of such a transaction with its value.
- **Not used:** 1inch's fees and amounts. Checked against receipts, its fees differed on BNB Smart Chain and Arbitrum and left out the L1 data fee on Base and OP, and its history missed a 23.3 POL payment on Polygon.
- Served on Ethereum, BNB Smart Chain, Polygon, OP, Arbitrum, Gnosis and Base; not on Celo or Ronin.

## What a refresh reads

- **Discovery:** on the first refresh and then weekly, balances and newest transfers are read in groups of five networks. They add the chains the address has used; chains the user chose are kept.
- **Skip check:** per chain, the newest transfer (plus the newest contract-made transfer where listed) and the sent count. If they equal the saved head, nothing else is read: two or three small requests per chain.
- **Full read:** otherwise every transfer page is read, then receipts for transactions the address may have sent. The history is applied in one database transaction, and the balances are then checked. "Recover full history" forces a full read.
- **1inch check:** with a 1inch key, its history is read on a full read and at least every 6 hours, even when Alchemy shows nothing new, since a contract can pay the address in someone else's transaction. A found payment triggers a full read.
- **Closable gaps:** an unchanged chain is also read in full while its note reports sent transactions still to be searched for, or contract payments not listed when a 1inch key is set.
- **Failures:** a network that fails or times out applies nothing, and the chain shows the error.

## Decoding

- **One activity per transaction and tracked address,** with net movements per asset. A transfer between two tracked addresses is two activities, which can be linked like any transfer.
- **Fees:** paid by the address that sent the transaction, according to the receipt. The fee is gas used × effective gas price, plus the L1 data fee on OP-stack chains such as Base and OP. A transfer that appears to come from the address but was sent by someone else (address poisoning) costs this address nothing.
- **Failed transactions:** a failed transaction found through a receipt moves no value but still costs its fee.
- **Tokens:** ERC-20 amounts use the token's decimals. NFTs are one asset per contract and token ID. Token names are cleaned of invisible characters used in look-alike scams.
- **No accounting treatment is assumed,** as for Bitcoin.

## Coverage gaps, shown per chain

- **Unlisted sent transactions:** Alchemy lists transfers, so a sent transaction that moved nothing (such as a failed call) is not listed. Every nonce up to the sent count belongs to one transaction, so each nonce no listed transaction uses is searched for:
  - The nonces of the listed sent transactions come from `eth_getTransactionByHash`.
  - A binary search over the address's historical transaction count (`eth_getTransactionCount` at a block, archive state) finds the block where the count passes the nonce, about 30 requests.
  - That block's transaction from the address with that nonce, and its receipt, give hash, fee and status. It is recorded with the source `nonce-search`.
  - At most 3 nonces are searched per chain and refresh. A nonce with no such transaction in its block (for example used by a delegation authorization) is recorded as not found and not searched again; the chain reports it as "could not be found on the chain".
- **Contract payments:** where Alchemy has no contract-made transfer list (BNB Smart Chain, Arbitrum, OP, Gnosis, Celo, Ronin):
  - With a 1inch key, each transaction in which 1inch's history shows a contract paying the address is traced once. Every successful `CALL` or `SELFDESTRUCT` inside it that moves native coin to or from the address is an internal transfer, identified by its order in the call tree and recorded with the source `1inch-trace`. Calls inside a failed call do not count; delegate and static calls move no value.
  - The transfers join their transaction's activity, so swap proceeds are part of the swap. A payment in a transaction Alchemy did not list is its own deposit.
  - Traces and searched nonces are saved in `evm_recovered`, which backups include.
  - Without a key, the chain says "Payments made by contracts are not listed on this chain", with a hint where 1inch could recover them.
- **Staking withdrawals** on Ethereum and Gnosis are not available. The decoder supports them for a provider that supplies them.
- **Native balance:** whatever the steps above cannot recover leaves the native coin off: contract payments without a 1inch key, sent transactions not found, staking withdrawals.
  - After each balance read, the ledger is brought to Alchemy's native balance with adjustments, shown as "Adjustment".
  - Wherever the running balance would fall below zero, the missing amount is an inflow 1 ms before that transaction, the latest moment it can have arrived. History therefore never goes negative.
  - The remaining difference follows 1 ms after the chain's last transaction.
  - The adjustments are derived again on every read and replace the previous ones. They have no cost basis.
  - A chain whose last check left a difference is read again even when nothing new was listed.
- **Balance check:** after each full read, every listed token balance is compared with Alchemy's, except hidden assets. Spam tokens' events need not add up. A difference shows on the chain rather than as a silently wrong total.
- **Rebasing tokens:** Lido stETH, ether.fi eETH, Origin Dollar and Ampleforth on Ethereum change balance without transfers.
  - On every refresh, including one with no new transfer, the ledger is brought to Alchemy's balance with one activity at the time of the check.
  - Growth of a yield token is a reward; other changes, including Ampleforth's supply adjustments and Lido's 1–2 wei transfer rounding, are adjustments.
  - The rewards are booked when seen rather than day by day, so earlier chart points show the transferred amount.

## Assets and coins

- **Assets are exact per chain:** `eip155:<chain>/native`, `eip155:<chain>/erc20:<contract>` and `eip155:<chain>/nft:<contract>:<tokenId>`.
- **CoinGecko links:** CoinGecko's keyless catalog (`/asset_platforms` and `/coins/list?include_platform=true`) links these assets to coins. ETH on Ethereum, Base and Arbitrum is therefore one coin, with a per-chain breakdown on its page.
  - The catalog is cached on the device for a week.
  - A contract listed under two coins on one chain is never linked.
  - Native coins take their symbol and name from CoinGecko.

## Hidden tokens

Tokens without a CoinGecko coin, and all NFTs, start hidden, because unsolicited tokens are often scams.

With a 1inch key, a token with a CoinGecko coin also starts hidden when 1inch's history leaves it out, since 1inch omits tokens it rates as scams (on the test wallet: junk airdrops such as Based Coin and Black Agnus on Base). The listing of every tracked address on the chain must be at least an hour newer than the token's first movement, so a token newer than the listing is not judged.

Hidden assets stay in the ledger and in accounting but are left out of values and totals, and are listed for review. Showing or hiding an asset is the user's choice and is kept in backups.

## Prices

- **Sources:** US dollar prices of linked coins come only from DefiLlama's keyless coin API (`coins.llama.fi`), addressed by CoinGecko coin ID. Other display and secondary currencies come from CoinGecko, which DefiLlama does not quote. One price is recorded for every asset linked to the coin. NFTs and unlinked tokens have no value.
- **Current prices:** DefiLlama's `/prices/current` (50 coins per request) for USD; CoinGecko's `/simple/price` for other currencies.
- **USD history (DefiLlama `/chart`):** each linked coin the ledger has moved, including coins sold since, gets daily prices from a day before its first movement to now, then hourly prices for the last 90 days.
  - A request returns at most 500 points, so a coin needs one daily request per 500 days. Held coins come first.
  - The covered range per coin and period is saved; each range is fetched once, and a range ending more than a day ago is extended to now.
  - Requests are one second apart. On desktop a background run makes up to 600 and waits out a rate limit twice; on Android each refresh makes up to 40.
  - A coin DefiLlama does not know, or days before its first price, stay without a value.
- **Other currencies' history (CoinGecko `/coins/{id}/market_chart`):** once per coin and currency, a year of daily prices and then 90 days of hourly prices, 6 seconds apart; desktop runs make up to 24 requests. The keyless API offers one year, so older values in these currencies stay unknown.
- **Logos:** Linked coins' logos come from one `/coins/markets` request by coin ID, kept for a week. Each linked asset shows its coin's logo.

## Portfolio chart

A chart point sums the holdings that have a fresh price at that time. A holding without one, such as a coin whose history is still being fetched, is left out. The point inspector says how many assets have no price. A point has no value only when no holding has a price. The money-weighted return in Insights still needs every price.

## Limits

- **Not decoded:** DeFi positions, liquidity tokens and bridges (L3).
- **History length:** a history longer than 400 pages per list is refused rather than truncated.
- **Unsupported chains:** chains outside the list above cannot be tracked.
