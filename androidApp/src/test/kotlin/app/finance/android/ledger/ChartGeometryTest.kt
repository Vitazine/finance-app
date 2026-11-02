package app.finance.android.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChartGeometryTest {
    @Test fun zeroTinyAndMaximumNeverOverflowGeometry() {
        assertEquals(0, chartUnits(0, 0))
        assertEquals(0, chartUnits(1, Long.MAX_VALUE))
        assertEquals(1000, chartUnits(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(999, chartUnits(Long.MAX_VALUE - 1, Long.MAX_VALUE))
    }

    @Test fun proportionalGeometryHasNoMoneyRoundingSideEffects() {
        assertEquals(250, chartUnits(25, 100))
        assertEquals(500, chartUnits(50, 100))
    }

    @Test fun invalidGeometryInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { chartUnits(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { chartUnits(101, 100) }
    }
}
