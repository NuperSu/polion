# Polion UI model

A standalone Kotlin Compose desktop design model based on the supplied `UI_TO_COPY` screenshots. It uses a black canvas, charcoal cards, bright change indicators, outline navigation, and crypto-only sample content.

The five views are **Home**, **Markets**, **Portfolio**, **Insights**, and **Following**. Navigation, scrolling, and range selection are available for reviewing the design. All prices, balances, events, changes, and analytics are fixed illustrative fixtures. Range selection changes the selected chip, not the chart data. Other controls are visual placeholders.

This module has no dependency on Polion's financial core or application hosts and does not open a portfolio database, make network requests, or modify the installed app. Coin marks, charts, and decorative shapes are drawn in Compose.

## Open the model

From the repository root, with JDK 25:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :uiModel:run
```

The window starts at a phone-like size, on the Portfolio view. Resize it to explore the desktop layout; at 850 dp and above, navigation moves into a sidebar. Narrow layouts retain the reference's bottom navigation. Long pages scroll, and horizontal card strips scroll independently.

## Render previews

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk SKIKO_RENDER_API=SOFTWARE \
  xvfb-run -a ./gradlew :uiModel:test
```

Renders are written to `uiModel/build/previews/`: all five phone screens, full-page Home/Markets/Insights, and the desktop Portfolio. `ModelPreviewTest` also verifies preview navigation. Use `:uiModel:ktlintCheck` for style checks.

Screenshots used as visual references remain in the user-supplied `UI_TO_COPY` directory. All six images were inspected in sequential tiles, including the full 9,226-pixel Home reference. Stock, fund, index and commodity sections are omitted or replaced with crypto content. Paid-tier badges are omitted from this model.
