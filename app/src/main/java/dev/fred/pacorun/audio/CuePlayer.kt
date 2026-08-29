package dev.fred.pacorun.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.RawRes
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.fred.pacorun.R
import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue

enum class AudioCue(@param:RawRes val resourceId: Int) {
    TOO_SLOW(R.raw.too_slow),
    TOO_FAST(R.raw.too_fast),
    GOOD_PACE(R.raw.good_pace),
    BAD_GPS(R.raw.bad_gps),
    GOOD_GPS(R.raw.good_gps),
    ROUTINE_START(R.raw.routine_start),
    RUN_IN_TEN(R.raw.run_in_ten),
    WALK_IN_TEN(R.raw.walk_in_ten),
    COUNTDOWN_BEEP(R.raw.countdown_beep),
    RUN_ROUTINE(R.raw.run_routine),
    WALK_ROUTINE(R.raw.walk_routine),
    ROUTINE_END(R.raw.routine_end),
    SPEED_UP(R.raw.speed_up),
    SLOW_DOWN(R.raw.slow_down),
}

fun Cue.toAudioCue(): AudioCue = when (this) {
    Cue.TOO_SLOW -> AudioCue.TOO_SLOW
    Cue.TOO_FAST -> AudioCue.TOO_FAST
    Cue.GOOD_PACE -> AudioCue.GOOD_PACE
    Cue.BAD_GPS -> AudioCue.BAD_GPS
    Cue.GOOD_GPS -> AudioCue.GOOD_GPS
}

fun RoutineCue.toAudioCue(): AudioCue = AudioCue.valueOf(name)

data class QueuedAudioCue(val cue: AudioCue, val volume: Float)

class AudioCueQueue {
    private val cues = ArrayDeque<QueuedAudioCue>()

    fun enqueue(cues: List<AudioCue>, volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        cues.forEach { this.cues.addLast(QueuedAudioCue(it, clampedVolume)) }
    }

    fun takeNext(): QueuedAudioCue? = cues.removeFirstOrNull()

    fun clear() = cues.clear()
}

class CuePlayer(context: Context) {
    private val queue = AudioCueQueue()
    private val resourcePackage = context.packageName
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .build()
    private var playing = false
    private var closed = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playNext()
            }
        })
    }

    fun enqueue(cues: List<AudioCue>, volume: Float) {
        if (closed || cues.isEmpty()) return
        queue.enqueue(cues, volume)
        if (!playing) playNext()
    }

    fun close() {
        if (closed) return
        closed = true
        queue.clear()
        player.release()
    }

    private fun playNext() {
        if (closed) return
        val next = queue.takeNext()
        if (next == null) {
            playing = false
            return
        }
        playing = true
        player.volume = next.volume
        player.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://$resourcePackage/${next.cue.resourceId}")))
        player.prepare()
        player.play()
    }
}
