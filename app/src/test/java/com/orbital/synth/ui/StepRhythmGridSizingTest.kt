package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepRhythmGridSizingTest {
    @Test fun nestedPhoneAndTabletWidthsKeepAllSixteenUniquePadsAtLeast48dp() {
        // Conservative space left after both host/panel horizontal insets.
        for (phoneWidth in listOf(350f, 360f, 390f, 480f, 600f)) {
            val available = phoneWidth - 70f
            val columns = ModulationRouteGridSizing.columnsForWidth(available)
            val indices = mutableListOf<Int>()
            repeat(ModulationRouteGridSizing.rowsFor(16, columns)) { row ->
                repeat(columns) { column ->
                    val index = ModulationRouteGridSizing.index(row, column, columns)
                    if (index < 16) indices += index
                }
            }
            assertEquals((0 until 16).toList(), indices)
            assertTrue(
                "STEP hit width under $phoneWidth dp phone",
                ModulationRouteGridSizing.hitWidthDp(available, columns) >= 48f,
            )
        }
    }

    @Test fun partialLastRowKeepsTheSameColumnBudgetInsteadOfStretchingOnePad() {
        val available = 280f // 350dp phone with nested panel padding.
        val columns = ModulationRouteGridSizing.columnsForWidth(available)
        assertEquals(5, columns)
        assertEquals(4, ModulationRouteGridSizing.rowsFor(16, columns))
        assertEquals(15, ModulationRouteGridSizing.index(3, 0, columns))
        assertEquals(52f, ModulationRouteGridSizing.hitWidthDp(available, columns), 0f)
    }
}
