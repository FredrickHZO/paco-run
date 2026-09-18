package dev.fred.pacorun.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationControlTest {
    @Test
    fun combinesMinutesAndSecondsIntoTotal() {
        assertEquals(73, durationTotal(1, 13))
        assertEquals(7, durationTotal(0, 7))
        assertEquals(761, durationTotal(12, 41))
    }

    @Test
    fun rejectsZeroOrNegativeTotal() {
        assertNull(durationTotal(0, 0))
        assertNull(durationTotal(-1, 0))
        assertNull(durationTotal(0, -1))
    }
}
