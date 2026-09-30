package dev.fred.pacorun.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.fred.pacorun.connector.R
import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.routine.RoutineCue
import dev.fred.pacorun.service.CueSignal

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

fun CueSignal.toAudioCue(): AudioCue = when (this) {
    is CueSignal.Pace -> cue.toAudioCue()
    is CueSignal.Routine -> cue.toAudioCue()
}

data class QueuedAudioCue(val cue: AudioCue, val volume: Float)

class AudioCueQueue {
    private val cues = ArrayDeque<QueuedAudioCue>()

    fun enqueue(cues: List<AudioCue>, volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        cues.forEach { this.cues.addLast(QueuedAudioCue(it, clampedVolume)) }
    }

    fun takeNext(): QueuedAudioCue? = cues.removeFirstOrNull()

    fun clear() = cues.clear()

    fun size(): Int = cues.size
}

// media3 1.9.3's automatic `handleAudioFocus` only supports USAGE_MEDIA/USAGE_GAME -- for
// USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (the right usage for spoken cues) it silently fails
// its own precondition check internally and never actually calls AudioManager, so other apps'
// volume was never ducked. Focus is requested by hand here instead.
interface AudioDucking {
    /** Returns true if focus was granted. False means the cue must not play unprotected. */
    fun requestDucking(onFocusLost: () -> Unit): Boolean
    fun releaseDucking()
}

// A full focus loss (AUDIOFOCUS_LOSS/_TRANSIENT, e.g. an incoming call) means someone else needs
// the speaker now -- CuePlayer stops and drops whatever's queued rather than pausing to resume
// mid-sentence later, or playing a stale cue once focus returns. See docs/handoff-2026-09-08.md.
class SystemAudioDucking(context: Context) : AudioDucking {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var onFocusLost: (() -> Unit)? = null
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                onFocusLost?.invoke()
            }
        }
        .build()

    override fun requestDucking(onFocusLost: () -> Unit): Boolean {
        this.onFocusLost = onFocusLost
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun releaseDucking() {
        onFocusLost = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

class CuePlayer(context: Context, private val ducking: AudioDucking = SystemAudioDucking(context)) {
    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CuePlayer must be created on the main thread"
        }
    }

    private val queue = AudioCueQueue()
    private val resourcePackage = context.packageName
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
        .build()
    private val playerHandler = Handler(player.applicationLooper)
    private var playing = false
    private var closed = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // `playing` guards against a stale STATE_ENDED delivery that lands after
                // stopAndDropOnFocusLoss() already stopped playback and cleared the queue.
                if (playbackState == Player.STATE_ENDED && playing) playNext()
            }
        })
    }

    fun enqueue(cues: List<AudioCue>, volume: Float) {
        if (cues.isEmpty()) return
        val cuesToEnqueue = cues.toList()
        onPlayerLooper { enqueueInternal(cuesToEnqueue, volume) }
    }

    fun close() = onPlayerLooper(::closeInternal)

    @androidx.annotation.VisibleForTesting
    internal fun queueSizeForTest(): Int = queue.size()

    @androidx.annotation.VisibleForTesting
    internal fun playbackStateForTest(): Int = player.playbackState

    private fun enqueueInternal(cues: List<AudioCue>, volume: Float) {
        if (closed) return
        if (volume <= 0f) return
        queue.enqueue(cues, volume)
        if (!playing) {
            // Always post rather than calling straight through: onFocusLost fires from inside
            // AudioManager's own OnAudioFocusChangeListener callback, and stopAndDropOnFocusLoss
            // calls back into AudioManager (abandonAudioFocusRequest) -- posting avoids doing
            // that reentrantly on the same call stack, matching media3's own AudioFocusManager.
            val granted = ducking.requestDucking(onFocusLost = { playerHandler.post(::stopAndDropOnFocusLoss) })
            if (granted) playNext() else queue.clear()
        }
    }

    private fun stopAndDropOnFocusLoss() {
        if (closed || !playing) return
        player.stop()
        queue.clear()
        playing = false
        ducking.releaseDucking()
    }

    private fun closeInternal() {
        if (closed) return
        closed = true
        if (playing) ducking.releaseDucking()
        queue.clear()
        player.release()
    }

    private fun onPlayerLooper(action: () -> Unit) {
        if (Looper.myLooper() == player.applicationLooper) action() else playerHandler.post(action)
    }

    private fun playNext() {
        if (closed) return
        val next = queue.takeNext()
        if (next == null) {
            playing = false
            ducking.releaseDucking()
            return
        }
        playing = true
        player.volume = next.volume
        player.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://$resourcePackage/${next.cue.resourceId}")))
        player.prepare()
        player.play()
    }
}
