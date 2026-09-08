package dev.fred.pacorun.ui.editor

import dev.fred.pacorun.settings.RunPreferences

data class RoutineEditorState(
    val original: RunPreferences,
    val draft: RunPreferences,
    val durationInputValid: Boolean = true,
)

val RoutineEditorState.canSave: Boolean
    get() = draft != original && draft.isValid && durationInputValid
