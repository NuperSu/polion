# Exchange accounts (Bybit)

Code: `core/…/exchange/` (`Bybit.kt` client and parsers, `BybitConnector.kt`, `BybitDecoder.kt`, `ExchangeCoins.kt`, `ExchangeSyncRepository.kt`, `Hmac.kt`), and in `PortfolioApplication`: `bybitTargets`, `setBybitKey`, `saveExchangeRows`, `applyBybitHistory`, `exchangeAssets`, `chooseExchangeCoin`. Transfer matching across assets: `core/…/reconcile/TransferResolution.kt` (`AmountScale`) and the transfer branch of `core/…/accounting/Fifo.kt`. Hosts: `BybitTransport` in `androidApp` and `desktopApp`. UI: `sharedUi/…/ExchangeAccountFlows.kt`. Tests: `BybitTest`, `BybitConnectorTest`, `ExchangeCoinsTest`, `FifoTransferTest`, `TransferLinkAccountingTest`, `BybitTransportTest`, `BybitUiTest`.

## Tracking an account

A Bybit account is added from Add or Connect with an API key and its secret. Before anything is saved, Bybit's `GET /v5/user/query-api` is asked what the key may do. Only a key with `readOnly = 1` is accepted. The key's user ID becomes the account's identity (target `bybit` / `uid`), so one Bybit account cannot be tracked twice, and a replacement key must belong to the same account.

The key and secret are device settings (`bybit_key:<account>`, `bybit_secret:<account>`). They are not in backups, so after a restore the account asks for its key again. They are sent only as Bybit's signing headers, never in a URL, message or log.

Bybit keys without a bound IP address expire after 90 days; the account shows the expiry date Bybit reports.

## Requests

Every request is a signed GET (HMAC-SHA256 over timestamp, key, receive window of 10 seconds and the query string as sent) to `https://api.bybit.com`. The host allows only these paths:

| Path | Window | Use |
|---|---|---|
| `/v5/user/query-api` | — | Key check: read-only, user ID, expiry, Unified account |
| `/v5/account/wallet-balance?accountType=UNIFIED` | — | Unified Trading wallet balances |
| `/v5/asset/transfer/query-account-coins-balance?accountType=FUND` | — | Funding wallet balances |
| `/v5/asset/asset-overview` | — | Balances in Earn, bots, copy trading, loans and other account types |
| `/v5/account/transaction-log` | 7 days, 2 years back (729 days; Bybit refuses older windows) | Every Unified wallet balance change, with the balance after it |
| `/v5/asset/fundinghistory` | 7 days | Every Funding wallet balance change, with the balance after it |
| `/v5/asset/deposit/query-record` | under 30 days | On-chain transaction and chain of deposits |
| `/v5/asset/withdraw/query-record` | under 30 days | On-chain transaction, chain and fee of withdrawals |
| `/v5/asset/exchange/query-convert-history` | pages | The two sides of converts |
| `/v5/asset/coin/query-info?coin=…` | — | Token contracts of a coin not matched yet |
| `/v5/market/time` | — | Bybit's clock, after it rejects a timestamp (unsigned) |

Requests are spaced 150 ms apart across the process, and deposit records 650 ms (Bybit allows 100 a minute). HTTP 403 means too many requests from the IP address; nothing is sent for 10 minutes. A `10006` answer waits a second and tries once more; a `10002` answer reads Bybit's time, keeps the offset and tries once more.

## What a refresh reads

1. The key check, then the three balance requests.
2. Each history stream forward from where it stopped, with one day of overlap for records Bybit adds late; then further back until the stream's start: two years for the Unified log (Bybit's retention), five years for the Funding history (it can be quiet for months while deposits and withdrawals happen), and for deposits and withdrawals until a year passes without a record, at most five years. A window Bybit refuses as too old ends that stream's history.
3. Convert history, newest first, until a page holds only known converts.
4. Token contracts of symbols not matched to a coin yet.

At most 400 requests are made per account and refresh; a first refresh needs about 500 and so takes two refreshes. Whatever remains continues with the next refresh, and the account says older history is still being read. Each record is saved as it arrives, as Bybit's JSON row (table `exchange_rows`) and as a raw provider record, so an interrupted refresh resumes and a record Bybit changes (a withdrawal that completes) replaces the saved one. Read progress per stream is in `exchange_sync`.

## From records to activities

The account's activities are derived again from every saved record on each refresh. Activity IDs are `bybit:<user ID>:<kind>:<record>`, so they stay the same across refreshes; activities no longer produced are superseded.

- **The two balance logs are the ledger.** Every balance change of the Unified wallet and the Funding wallet is one of them.
- **Moves between the wallets:** a Unified log transfer and a Funding row of the same coin and opposite amount, at most 10 minutes apart, are the two sides of one move and produce no activity. A side without its pair (before one wallet's history starts) is a transfer.
- **Trades:** Unified log rows of one spot trade (one per coin, sharing a trade ID) become one trade. Each leg is the row's change before its fee; the fee is its own movement, and a negative fee (a rebate) is a reward. Conversions inside the Unified wallet (`CURRENCY_BUY`/`SELL` and similar) are grouped by order.
- **Converts in the Funding wallet:** the two rows that match a successful convert record by coin, amount and time become one trade.
- **Deposits and withdrawals:** Funding rows are matched to deposit and withdrawal records by coin, amount and time (within 6 hours). The record adds the on-chain transaction and chain. A withdrawal whose debit equals amount plus fee becomes a transfer of the amount and a separate fee movement, so its amount equals what arrives on chain.
- **Rewards:** airdrops, bonuses, fee refunds, Earn interest and Launchpool yield are rewards.
- **Moves into and out of Bybit products** (Earn, Launchpool, trading bots, copy trading, MT5 and the old Spot account) are transfers. These products have no history of their own, and coins do not always return the way they went (Earn principal can be spent by the card or sold), so recording each move keeps the two wallets' balances exact over time. What the products hold now is the remainder.
- **Card spending and ATM withdrawals** are payments. P2P sales and purchases, Dual Asset plans and their settlement are kept as other activity.
- **Everything else** (derivatives settlement and funding, borrowing, unknown types) is kept as it is, as other activity, never dropped.

### Opening balances and remainder

Each log records the balance after every row, so each wallet's balance before its first row is known: the current balance less every change since. It is recorded as one opening amount per wallet and coin, 1 ms before that wallet's first row. Coins held where there is no history (Earn, bots, copy trading, loans), and anything the records do not explain, are one adjustment per coin just after the latest record, derived again on each refresh. With complete logs the remainder is what sits in those products. An activity that moves a coin still waiting for the user's choice is left out until then, so its other side shows in the remainder meanwhile.

## Coins

An exchange balance is a claim on a coin, not a token of one chain, so every exchange shares one asset per CoinGecko coin: `coin:<coingecko-id>`, with 18 decimals. Bybit reports BTC to 14 decimals, so Bybit BTC is `coin:bitcoin`, not the 8-decimal `bitcoin:btc` of Bitcoin wallets. Prices and logos come through the asset's coin link.

Every coin Bybit holds is tracked once it is matched to a CoinGecko coin. A symbol becomes a coin through a short list of well-known symbols (BTC, ETH, USDT, USDC, …); else through its token contracts: Bybit's coin information lists each chain the coin can be deposited on with its contract, and CoinGecko's contract map names the coin when all of them agree (EVM networks, found by Bybit's chain code or by the chain's name); else the only CoinGecko coin with that symbol; else the one ranked highest by market capitalization in the cached market list; else the one whose CoinGecko ID is the symbol itself. Up to 50 unmatched symbols are looked up per refresh. Non-EVM contracts are matched on TON, Solana, Tron, NEAR, Sui and Aptos.

A symbol no CoinGecko coin carries is counted as `unlisted:<exchange>:<SYMBOL>`, without a price. When several coins carry a symbol and none of the rules decides, the account asks which coin it is, or "none of these", which counts it without a price.

Currencies (EUR, USD, GBP) are `fiat:<CODE>` with 18 decimals, since Bybit counts EUR to 8 decimals; each is worth one of the portfolio currency of the same code. A symbol none of these decides waits for the user, who picks the coin on the account; its records are left out until then. Matches are saved in `exchange_coin_map`; a user's choice is never replaced. An amount finer than 18 decimals fails that record, with a note on the account.

## Transfers between Bybit and wallets

A withdrawal and its arrival in a tracked wallet can be different assets of one coin (Bybit's `coin:tether` and USDT on Ethereum). Transfer suggestions and links compare such amounts as decimals of the coin (`AmountScale`), so they match only when the amounts are exactly equal. A pair that shares an on-chain transaction (Bybit's record and a Bitcoin txid or EVM hash) is suggested with the top score.

An accepted link moves lots from the outgoing asset to the incoming one with their cost and acquisition dates. Lot quantities follow the incoming asset's decimals; parts that round away are given to the last part, so the moved lots add up to exactly what arrived.

## Not covered

- Derivatives positions are not modeled; their realized results, funding and fees change balances as other activity.
- Earn rewards that stay inside Earn have no history and appear in the remainder.
- Bybit CSV exports, Binance and MEXC are planned. A later Bybit CSV importer is meant to produce the same activity IDs for records the API also returned, so it fills only the older gap.
