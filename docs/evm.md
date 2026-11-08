# EVM addresses (Ethereum and other chains)

Code: `core/…/evm/` (identity, Alchemy client, decoder, connector, chain state), `core/…/market/CoingeckoCatalog.kt` and `AssetLinkRepository.kt`, and in `PortfolioApplication`: `applyEvmChainHistory`, `checkEvmBalances`, `refreshEvmCatalog`, `refreshLinkedCoinPrices`. Hosts: `AlchemyTransport` in `androidApp` and `desktopApp`. Tests: `EvmIdentityTest`, `AlchemyTest`, `EvmConnectorTest`, `EvmAssetsApplicationTest`, `CoingeckoCatalogTest`, `AlchemyTransportTest`, `EvmUiTest`.

## Tracking an address

An EVM address is tracked once and covers every supported chain it is used on. It is validated as `0x` plus 40 hexadecimal characters. A mixed-case address must carry a correct EIP-55 checksum, so a mistyped character is caught. It is stored in lower case.

## Provider

History comes from Alchemy, using the user's free key from alchemy.com. In the Alchemy dashboard the networks used must be enabled for the key's app. The key stays on the device, is not part of backups, and is never shown in messages. Requests need the EVM consent; Alchemy receives the address and the device's IP address. The host paces requests at 5 per second and pauses after a rate limit.

- **Transfers By Wallet** lists transfers: native coin, ERC-20, ERC-721 and ERC-1155. Contract-made (internal) transfers are listed on Ethereum, Base and Polygon only.
- **Tokens By Wallet** gives native and ERC-20 balances.
- **Per-network JSON-RPC** gives transaction receipts (fee and status) and the count of transactions an address has sent.

Supported chains: Ethereum, BNB Smart Chain, Base, Polygon, Arbitrum One, OP Mainnet, Gnosis, Celo and Ronin.

## What a refresh reads

- **Discovery:** on the first refresh and then weekly, balances and newest transfers are read in groups of five networks. They add the chains the address has used; chains the user chose are kept.
- **Skip check:** per chain, the newest transfer (plus the newest contract-made transfer where listed) and the sent count. If they equal the saved head, nothing else is read: two or three small requests per chain.
- **Full read:** otherwise every transfer page is read, then receipts for transactions the address may have sent. The history is applied in one database transaction, and the balances are then checked. "Recover full history" forces a full read.
- **Failures:** a network that fails or times out applies nothing, and the chain shows the error.

## Decoding

- **One activity per transaction and tracked address,** with net movements per asset. A transfer between two tracked addresses is two activities, which can be linked like any transfer.
- **Fees:** paid by the address that sent the transaction, according to the receipt. The fee is gas used × effective gas price, plus the L1 data fee on OP-stack chains such as Base and OP. A transfer that appears to come from the address but was sent by someone else (address poisoning) costs this address nothing.
- **Failed transactions:** a failed transaction found through a receipt moves no value but still costs its fee.
- **Tokens:** ERC-20 amounts use the token's decimals. NFTs are one asset per contract and token ID. Token names are cleaned of invisible characters used in look-alike scams.
- **No accounting treatment is assumed,** as for Bitcoin.

## Coverage gaps, shown per chain

- **Unlisted sent transactions:** Alchemy lists transfers, so a sent transaction that moved nothing (an approval, or a failed call) is not listed and its fee is unknown. The sent count minus the sent transactions found is reported as "N sent transactions not listed by Alchemy; their fees are not counted".
- **Contract payments:** where Alchemy has no contract-made transfer list, the chain says "Payments made by contracts are not listed on this chain".
- **Staking withdrawals** on Ethereum and Gnosis are not available. The decoder supports them for a provider that supplies them.
- **Balance check:** after each full read, the ledger's native balance and every listed token balance are compared with Alchemy's, except hidden assets. Spam tokens' events need not add up. Any of the gaps above shows as a difference rather than a silently wrong total.

## Assets and coins

- **Assets are exact per chain:** `eip155:<chain>/native`, `eip155:<chain>/erc20:<contract>` and `eip155:<chain>/nft:<contract>:<tokenId>`.
- **CoinGecko links:** CoinGecko's keyless catalog (`/asset_platforms` and `/coins/list?include_platform=true`) links these assets to coins. ETH on Ethereum, Base and Arbitrum is therefore one coin, with a per-chain breakdown on its page.
  - The catalog is cached on the device for a week.
  - A contract listed under two coins on one chain is never linked.
  - Native coins take their symbol and name from CoinGecko.

## Hidden tokens

Tokens without a CoinGecko coin, and all NFTs, start hidden, because unsolicited tokens are often scams. Hidden assets stay in the ledger and in accounting but are left out of values and totals, and are listed for review. Showing or hiding an asset is the user's choice and is kept in backups.

## Prices

Linked coins are priced from CoinGecko's keyless `/simple/price` in the display and secondary currencies. One price is recorded for every asset linked to the coin. NFTs and unlinked tokens have no value.

## Limits

- **Not decoded:** DeFi positions, liquidity tokens and bridges (L3).
- **History length:** a history longer than 400 pages per list is refused rather than truncated.
- **Unsupported chains:** chains outside the list above cannot be tracked.
