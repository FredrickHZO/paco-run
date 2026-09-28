package dev.fred.pacorun.service

import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue
import org.junit.Assert.assertEquals
import org.junit.Test

class CueSignalTest {
    @Test
    fun `Pace delegates name to the wrapped Cue`() {
        assertEquals("TOO_FAST", CueSignal.Pace(Cue.TOO_FAST).name)
    }

    @Test
    fun `Routine delegates name to the wrapped RoutineCue`() {
        assertEquals("ROUTINE_START", CueSignal.Routine(RoutineCue.ROUTINE_START).name)
    }
}
