package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulationRouteGridSizingTest {
    @Test fun compactPhoneWidthsKeepEveryCellAtLeast48dpInsidePanelGutters() {
        // Panel itself has 12dp side padding; model takes *inside* width.
        for (phoneWidthDp in listOf(350f, 360f)) {
            val insideDp = phoneWidthDp - 24f
            val columns = ModulationRouteGridSizing.columnsForWidth(insideDp)
            assertEquals("Compact $phoneWidthDp phone must reflow into 6 columns",
                6, columns)
            assertTrue("Inside width $insideDp cannot shrink physical clickable area",
                ModulationRouteGridSizing.hitWidthDp(insideDp, columns) >= 48f)
            assertEquals(11, ModulationRouteGridSizing.rowsFor(64, columns))
        }
    }

    @Test fun everyUsableWidthAccountsForFourDpGuttersWithoutUndersizedControls() {
        for (phoneWidthDp in listOf(320f, 350f, 360f, 390f, 411f, 416f, 440f, 600f)) {
            val insideDp = phoneWidthDp - 24f
            val columns = ModulationRouteGridSizing.columnsForWidth(insideDp)
            assertTrue(columns in 1..8)
            assertTrue("width=$phoneWidthDp cols=$columns after 2+2dp gutters",
                ModulationRouteGridSizing.hitWidthDp(insideDp, columns) >= 48f)
        }
        assertEquals(1, ModulationRouteGridSizing.columnsForWidth(Float.NaN))
        assertEquals(1, ModulationRouteGridSizing.columnsForWidth(Float.NEGATIVE_INFINITY))
        assertEquals(8, ModulationRouteGridSizing.columnsForWidth(1_000f))
    }

    @Test fun rowMajorAdaptiveRemapKeepsAll64SerializedIdsUniqueAndOrdered() {
        for (columns in 1..8) {
            val indices = buildList {
                repeat(ModulationRouteGridSizing.rowsFor(64, columns)) { row ->
                    repeat(columns) { col ->
                        val index = ModulationRouteGridSizing.index(row, col, columns)
                        if (index < 64) add(index)
                    }
                }
            }
            assertEquals("Grid reflow must preserve slot numbers, even in final partial row",
                (0..63).toList(), indices)
        }
        assertEquals(0, ModulationRouteGridSizing.rowsFor(0, 6))
        assertEquals(1, ModulationRouteGridSizing.rowsFor(1, 6))
        assertEquals(11, ModulationRouteGridSizing.rowsFor(64, 6))
        assertEquals(8, ModulationRouteGridSizing.rowsFor(64, 8))
    }
}
