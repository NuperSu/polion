package io.polion.ui

import androidx.compose.runtime.compositionLocalOf

/** What a hidden amount shows: the value is replaced, not blurred, so it cannot be recovered from the screen. */
internal const val HIDDEN_AMOUNT = "*****"

/** True when the user hid balances with the eye button; percentages, charts and market prices stay visible. */
internal val LocalAmountsHidden = compositionLocalOf { false }

/** Unknown values ("—") stay as they are, so hiding never suggests a value exists. */
internal fun String.hiddenIf(hide: Boolean): String = if (hide && this != "—") HIDDEN_AMOUNT else this
