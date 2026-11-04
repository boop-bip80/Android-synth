package com.orbital.synth.ui

/**
 * Pure, density-independent layout policy for the 64-route patch bank.
 *
 * Eight fixed columns make a nominal 360dp phone's hit boxes only ~41dp wide.
 * Give every actual clickable cell at least 48dp *after* its 2dp side gutters,
 * and reflow into additional vertically scrollable rows instead of shrinking
 * the tap target. The host's parent scroll remains the only vertical scroller.
 */
internal object ModulationRouteGridSizing {
    const val ROUTES = 64
    const val MAX_COLUMNS = 8
    const val MIN_TOUCH_DP = 48f
    const val CELL_GUTTER_DP = 2f
    const val MIN_COLUMN_BUDGET_DP = MIN_TOUCH_DP + CELL_GUTTER_DP * 2f

    fun columnsForWidth(availableWidthDp: Float): Int {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 1
        return (availableWidthDp / MIN_COLUMN_BUDGET_DP)
            .toInt().coerceIn(1, MAX_COLUMNS)
    }

    fun rowsFor(routeCount: Int, columns: Int): Int {
        require(columns in 1..MAX_COLUMNS)
        return if (routeCount <= 0) 0 else (routeCount - 1) / columns + 1
    }

    /** Exact physical button width AFTER each side's 2dp visual gutter. */
    fun hitWidthDp(availableWidthDp: Float, columns: Int): Float {
        require(columns in 1..MAX_COLUMNS)
        return (availableWidthDp / columns - CELL_GUTTER_DP * 2f).coerceAtLeast(0f)
    }

    fun index(row: Int, column: Int, columns: Int): Int {
        require(columns in 1..MAX_COLUMNS && row >= 0 && column in 0 until columns)
        return row * columns + column
    }
}
