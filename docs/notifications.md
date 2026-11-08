# Price alerts and notifications

Code: `core/…/alerts/Notifications.kt` (rules, repository, text), `PortfolioApplication.evaluateNotifications` / `runNotificationCheck`. Tests: `NotificationsTest`, `OrderBookApplicationTest`.

## Price source

Every alert and notification uses CoinGecko's cached top-250 market list for the display currency, the same list Main shows. A check refreshes that list when it is older than 10 minutes, so alerts add at most two CoinGecko requests per check. A list older than one hour is never evaluated, so stale prices never trigger anything. Alerts are therefore available only for coins in the top 250 and need market data consent.

## Price alerts

An alert watches one coin in one list currency:

- **Above / Below:** fires when the price reaches the threshold. A new alert whose condition already holds waits for the next crossing. After firing it disarms and re-arms once the price is back on the other side, so each crossing alerts once.
- **Moves by %:** fires when the price is the given percentage away from its reference price, which starts at the price when the alert is saved and resets to the price of each alert.
- **Cooldown:** each alert fires at most once an hour.
- **Once per snapshot:** an alert records the market snapshot it last evaluated; the same snapshot never changes it twice.

Editing an alert restarts it from the current price. Alerts for another currency than the display currency wait until that currency is shown again.

## Notification settings

All are off by default.

- **Daily portfolio update:** once per local day from 9:00, the portfolio value and its 24-hour money-weighted return. While amounts are hidden, only the return is shown.
- **Significant price changes:** 24-hour moves of 10% or more for coins you hold or favorite; at most once per coin, direction and local day.
- **All-time highs and lows:** a coin you hold or favorite whose CoinGecko all-time high or low was set within the last day; at most once per coin per day the record was set. 52-week highs and lows are not offered: CoinGecko's list has no 52-week figures, and history requests per coin would exceed its limits.

## Delivery

Each notification has a unique key (for example the alert and snapshot, or the coin and day). The key and the alert states are written in one transaction before the host shows anything, so restarts, retries and two processes checking at once never deliver the same event twice. A notification lost by the operating system after it is recorded is not re-sent. Settings › Notifications lists the last 20; 500 are kept.

## Timing limits

- **Desktop:** checks run while Polion is open, 30 seconds after launch and every 5 minutes. Nothing runs while it is closed.
- **Android:** a background check every 15 minutes at best, with a network connection. Android may delay or batch checks when the phone is idle or in battery saver, and notifications need the notification permission. Polion also checks when it is opened.

Neither platform monitors prices continuously; an alert reports a crossing seen at a check, not the moment it happened.
