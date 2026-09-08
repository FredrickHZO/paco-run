package dev.fred.pacorun.ui.editor

import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineEditorStateTest {
    @Test
    fun unchangedDraftCannotSave() {
        val p = RunPreferences()
        assertFalse(RoutineEditorState(p, p).canSave)
    }

    @Test
    fun validChangedDraftCanSave() {
        val p = RunPreferences(mode = RoutineMode.WALK_RUN)
        assertTrue(RoutineEditorState(p, p.copy(runSeconds = 73)).canSave)
    }

    @Test
    fun invalidChangedDraftCannotSave() {
        val p = RunPreferences(mode = RoutineMode.WALK_RUN)
        assertFalse(RoutineEditorState(p, p.copy(runSeconds = 0)).canSave)
    }

    @Test
    fun invalidDurationInputBlocksSaveEvenIfDraftValid() {
        val p = RunPreferences()
        val state = RoutineEditorState(p, p.copy(runSeconds = 73), durationInputValid = false)
        assertFalse(state.canSave)
    }
}
