package dev.fred.pacorun.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPreferencesTest {
    @Test
    fun defaultsMatchSpec() {
        assertEquals(RunPreferences(7.5f, 8.5f, 2, 5_000L, false, .6f, true), RunPreferences())
    }

    @Test
    fun minimumMustBeLowerThanMaximum() {
        assertTrue(RunPreferences(minKmh = 8f, maxKmh = 8f).isValid.not())
        assertTrue(RunPreferences(minKmh = 8.1f, maxKmh = 8f).isValid.not())
        assertTrue(RunPreferences(minKmh = 7.9f, maxKmh = 8f).isValid)
    }
}
