package io.polion.ui

import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetSort
import io.polion.core.application.AssetValuation

/** Display filtering never alters the underlying balance projection. */
internal fun displayedAssets(
    valuations: List<AssetValuation>,
    preference: AssetListPreference,
): List<AssetValuation> {
    val visible = valuations.filterNot { preference.hideEmpty && it.amount.atomicUnits.sign == 0 }
    return if (preference.sort == AssetSort.NAME) {
        visible.sortedWith(compareBy({ (it.metadata?.name ?: it.asset.value).lowercase() }, { it.asset.value }))
    } else {
        visible.sortedWith { a, b ->
            val aValue = a.value
            val bValue = b.value
            when {
                aValue == null && bValue != null -> 1
                aValue != null && bValue == null -> -1
                aValue != null && bValue != null -> bValue.compareTo(aValue).takeIf { it != 0 } ?: a.asset.value.compareTo(b.asset.value)
                else -> a.asset.value.compareTo(b.asset.value)
            }
        }
    }
}
