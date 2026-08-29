package dev.fred.pacorun.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fred.pacorun.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CueAssetsTest {
    @Test
    fun allCueResourcesOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            R.raw.too_slow,
            R.raw.too_fast,
            R.raw.good_pace,
            R.raw.bad_gps,
            R.raw.good_gps,
            R.raw.routine_start,
            R.raw.run_in_ten,
            R.raw.walk_in_ten,
            R.raw.run_routine,
            R.raw.walk_routine,
            R.raw.routine_end,
            R.raw.speed_up,
            R.raw.slow_down,
            R.raw.countdown_beep,
        )
            .forEach { resourceId ->
                context.resources.openRawResource(resourceId).use { input ->
                    assertTrue(input.read() >= 0)
                }
            }
    }
}
