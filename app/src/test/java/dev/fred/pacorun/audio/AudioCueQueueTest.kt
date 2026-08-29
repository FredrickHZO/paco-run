package dev.fred.pacorun.audio

import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCueQueueTest {
    @Test
    fun convertsPaceAndRoutineCuesToAudioCues() {
        assertEquals(AudioCue.TOO_SLOW, Cue.TOO_SLOW.toAudioCue())
        assertEquals(AudioCue.ROUTINE_START, RoutineCue.ROUTINE_START.toAudioCue())
        assertEquals(AudioCue.COUNTDOWN_BEEP, RoutineCue.COUNTDOWN_BEEP.toAudioCue())
    }

    @Test
    fun enqueuesRoutineStartAndSpeedUpWithoutReplacingEitherCue() {
        val queue = AudioCueQueue()

        queue.enqueue(listOf(AudioCue.ROUTINE_START, AudioCue.SPEED_UP), 1.5f)

        assertEquals(
            listOf(
                QueuedAudioCue(AudioCue.ROUTINE_START, 1f),
                QueuedAudioCue(AudioCue.SPEED_UP, 1f),
            ),
            listOf(queue.takeNext(), queue.takeNext()),
        )
    }

    @Test
    fun preservesFirstInFirstOutOrderAcrossEnqueues() {
        val queue = AudioCueQueue()

        queue.enqueue(listOf(AudioCue.WALK_ROUTINE), .25f)
        queue.enqueue(listOf(AudioCue.COUNTDOWN_BEEP, AudioCue.RUN_ROUTINE), .75f)

        assertEquals(
            listOf(
                QueuedAudioCue(AudioCue.WALK_ROUTINE, .25f),
                QueuedAudioCue(AudioCue.COUNTDOWN_BEEP, .75f),
                QueuedAudioCue(AudioCue.RUN_ROUTINE, .75f),
            ),
            listOf(queue.takeNext(), queue.takeNext(), queue.takeNext()),
        )
    }
}
