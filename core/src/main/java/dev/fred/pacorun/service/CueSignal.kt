package dev.fred.pacorun.service

import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue

sealed interface CueSignal {
    val name: String
    data class Pace(val cue: Cue) : CueSignal {
        override val name get() = cue.name
    }
    data class Routine(val cue: RoutineCue) : CueSignal {
        override val name get() = cue.name
    }
}
