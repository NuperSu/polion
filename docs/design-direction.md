# Original design direction

This Phase 1 proposal guides presentation work; it does not describe a completed theme or new metrics. The current application uses a light palette, teal/ink accents, rounded panels and responsive Overview/Activity/Accounts/Reports/Settings pages. See [UI map](ui-map.md) for the migration and [product contract](product-contract.md) for capability claims.

## Visual hierarchy

Use an original, restrained financial workspace: dark, light and automatic modes; clear typography; compact status language; and generous space around the headline while keeping holdings rows dense enough to compare. Deep neutral surfaces, a distinct teal action color and separate semantic gain/loss colors are proposed. Do not rely on green/red alone: include signs, labels and accessible descriptions.

Define shared tokens for surface/text/border/action/status colors, spacing, shape, typography, elevation, chart strokes, focus/hover and icon sizes. Initial layout examples can use a 4 dp spacing rhythm, 16 dp phone gutters and 24 dp wide-pane gutters; verify actual fit and text scaling before treating these as final tokens. Keep financial tables aligned, with exact values available through accessible detail rather than clipping them without recourse.

Use licensed neutral icons or original marks. Wallet/exchange brand names describe optional metadata; their logos require a redistribution review. Do not copy reference artwork, exact card layouts, proprietary glyphs, copy or animations.

## Content examples

The following are wording/layout examples, not real portfolio data:

```text
Portfolio                              USD
Priced value                       $12,340
2 assets unpriced · View coverage
Observed 10:42 · History coverage partial

[Historical value]                     [1M]
Chart gaps indicate unavailable valuations

Bitcoin · Bitcoin mainnet
0.125 BTC                          $12,340
Quote observed 10:40 · Provider …
```

The hero says “Priced value” when it excludes unpriced assets. With no usable values show an unavailable marker and reason, not an invented zero. Chart tooltips should expose exact timestamp/value and coverage; gaps remain gaps. A separate performance view is introduced only when a tested money-flow-aware projection exists.

```text
Source attention
Refresh interrupted
Saved portfolio data remains available.
[Retry refresh] [View details]

Accounting
Unrealized P&L unavailable
Acquisition basis is missing for part of this holding.
[Review basis]
```

Do not use “P&L ready,” “all history found” or “fully connected” as generic completion copy. Do not invent portfolio health scores or financial advice. A timestamp needs an explicit fact: “Quote observed …” or “Refresh accepted …,” not an ambiguous “Updated.”

## Reusable surfaces

Build status rows, coverage explanations, priced-value summaries, exact-quantity rows, source progress, empty states, retry actions and provenance detail before composing larger dashboards. Saved data remains readable during refresh and failure. Distinguish skeleton/loading state from known empty state and failed load.

Compact layouts prioritize the headline, attention items and core assets; contextual detail opens as a screen or dialog with accessible back/dismiss behavior. Wide layouts place tables and detail panes side by side. Supported analytical cards can later gain saved show/hide/order controls without changing their projection definitions.

## Verification for implementation phases

Static previews must use labeled synthetic fixtures covering priced, unpriced, stale, partial, offline, rate-limited, denied, error and empty states. Add screenshot/semantics tests when the components are implemented. Check dark/light contrast, text scaling, 320 dp width, landscape, long names/quantities, keyboard focus, screen-reader labels and reduced motion. Financial values remain exact in domain/state; conversion to drawing coordinates is permitted only at the chart boundary.

The reference screenshots are available here only through their roadmap descriptions. These examples are original proposals based on that description and the current Polion product; they do not certify pixel comparison or final visual approval.
