# Paco Run — Material 3 Expressive UI Design

**Date:** 2026-08-30  
**Status:** Approved in conversation; awaiting written-spec review  
**Target:** Pixel 9a, 1080 × 2424, 20:9  

## Objective

Replace Paco Run's deliberately minimal functional interface with an interface fully grounded in Material 3 Expressive (M3E). The result must use the actual Android Compose Material 3 implementation and official expressive components, tokens, shapes, typography, motion, and color roles. It must not imitate M3E with arbitrary custom decoration.

The UI is optimized for the real usage pattern: the user normally reuses the last routine and wants to start quickly. Configuration remains complete but secondary. The phone is normally used with its screen off during the workout, but every visible state must still be polished and useful.

## Decision Authority

- Follow explicit M3E specifications and recommendations where they exist.
- When M3E permits multiple valid product choices, present the alternatives and a recommendation to the user; do not silently decide.
- Build visual truth in real Compose on a Pixel 9a emulator. HTML mockups from the brainstorming session are rejected and are not design references.
- Inspect every rendered screen before presenting it for approval.

## Material 3 Expressive Language

- Use the high-expression end of M3E: emphasized typography, strong scale contrast, responsive components, contrasting official shapes, and prominent primary actions.
- Use official M3E component APIs wherever available, including expressive button groups, loading/progress indicators, large buttons, toolbars, and component motion.
- Use shapes from the M3E shape system and component defaults. Custom shapes are allowed only when an official component requires configuration and the shape communicates hierarchy or state.
- Use M3E motion physics, spring responses, shape morphing, and coordinated container transitions. Motion must communicate state or continuity; decorative motion without meaning is excluded.
- Use Material Symbols, never emoji or improvised text glyphs as icons.
- Use tonal containment only to group related information or establish hierarchy.
- Do not add bottom navigation until real destinations such as history or statistics exist.

## Color and Theme

- Follow the Pixel's system light/dark theme.
- Preserve Dynamic Color as a user option and use Android's real generated scheme when enabled.
- Provide three fixed M3 color schemes for visual comparison in real Compose. Each must be a complete light/dark scheme generated and mapped through official M3 color roles rather than per-component color overrides.
- Select the final fixed scheme only after the user sees the same real screen rendered in all three schemes.
- Run, Walk, GPS, warning, and error states must remain understandable without color alone.
- Use the Material error roles for validation and errors.
- Verify contrast in both themes before acceptance.

## Information Architecture

Paco Run has four primary screens:

1. **Home / Ready** — reuse and start the last routine.
2. **Edit routine** — dedicated editor for every routine and guidance setting.
3. **GPS acquisition** — short transition between Start and an active session.
4. **Active session** — glanceable live routine state and protected Stop action.

Navigation is linear and shallow. Home opens Edit routine. Start opens GPS acquisition. The first reliable fix automatically opens Active session and begins the session. Stop returns to Home.

## Home / Ready

Hierarchy, highest to lowest:

1. Extra-large **Start** action.
2. Current mode and last-routine summary.
3. Technical GPS readiness pill.
4. Secondary **Edit routine** action.

The routine summary shows only information that helps identify a wrong routine before starting:

- Constant or Walk–Run mode.
- Constant speed range.
- For Walk–Run: initial constant distance, Run duration and speed range, Walk duration and effective speed range, and limited/unlimited routine distance.

Audio volume, repeat interval, confirmation samples, and other technical values stay out of Home.

The GPS pill does not start a location request. It reports technical readiness only:

- `GPS ready`: precise-location permission granted and Android location enabled.
- `Location off`.
- `Permission needed`.
- `Approximate only` when precise access is absent.

Real signal quality begins only after the explicit Start action. This preserves battery, privacy, and Google Play's user-initiated location expectations.

## Edit Routine

Editing happens on a dedicated screen. Values are held as a draft and persisted only by an explicit **Save routine** action.

- A M3E ButtonGroup selects Constant or Walk–Run.
- Sections follow workout order: initial Constant, Run, Walk, routine end, voice and GPS.
- Irrelevant sections enter and leave with meaningful animated transitions.
- Speed range is one paired Min/Max control.
- `−` and `+` adjust speed by 0.1 km/h; direct numeric entry remains available and unrestricted by arbitrary bounds.
- Duration uses one large `MM:SS` control with independently selectable minutes and seconds.
- Any duration of at least one second is valid, including unusual values such as `0:07` or `1:13`. No arbitrary maximum or forced increment exists.
- Walk may reuse Constant thresholds or expose its own range.
- Routine may be unlimited or expose a positive distance.
- Validation is inline. Save is disabled while invalid.
- Back with an edited draft offers **Continue editing** or **Discard**. Android Back is the cancel mechanism; no redundant permanent Cancel button is required.
- Successful Save returns to Home and updates the summary.

## GPS Acquisition

Start is an explicit user action. It launches the foreground location session and opens GPS acquisition.

- Show an official expressive loading/progress component and a clear `Searching for GPS` state.
- Provide **Cancel**.
- The first reliable GPS fix automatically starts timing and distance, then transitions into Active session.
- There is no second `Go` command and no additional countdown.
- Use a meaningful shape/color transition and system-respecting haptic feedback when acquisition completes.

## Active Session

### Timed Run/Walk phases

Hierarchy:

1. Remaining interval countdown as the hero element.
2. Current `RUN` or `WALK` phase.
3. Current speed and active target range.
4. Real GPS state, total distance, and routine distance.
5. Protected Stop action.

An official expressive progress indicator represents actual remaining interval progress. It must not be a decorative waveform disconnected from data.

### Constant phases

Constant mode, initial Constant, and Constant-after-routine have no interval countdown. Current speed becomes the hero. At the start or end of a timed routine, the hero container morphs between speed and countdown while preserving spatial continuity.

### Pace and GPS state

`Too slow`, `Too fast`, and `Good pace` produce coordinated visual changes using text, shape, color role, and motion. Color is never the only indicator. `GPS weak` is visible while the existing behavior continues: timers advance, distance and pace evaluation pause.

### Stop protection

Stop requires a press-and-hold gesture:

- The container expands/fills to show progress.
- Shape morphing and system-respecting haptics communicate start and completion.
- Releasing early cancels the action.
- Copy changes during the gesture to explain that release cancels.

The active screen does not expose unnecessary audio controls.

## Motion and Transitions

- Start transforms into GPS acquisition; acquisition transforms into the session hero.
- Edit sections use coordinated enter/exit motion when mode or toggles alter visibility.
- The active hero morphs between speed and countdown at routine boundaries.
- ButtonGroup selections use the official shape-shifting interaction.
- Press-and-hold Stop has continuous visible progress and haptic milestones.
- Respect the platform's reduced-motion preference: preserve state clarity while reducing or removing large spatial movement and spring overshoot.

## Accessibility

- Support Android font scaling without clipping or hiding required controls.
- Use accessible contrast for every fixed and dynamic light/dark scheme.
- Use at least the M3E-recommended touch targets.
- Provide concise TalkBack labels and state descriptions.
- Never encode Run, Walk, GPS, pace, validation, or action state solely in color.
- Follow system haptic preferences.
- Keep familiar interaction patterns and visible text labels; expression must not reduce usability.

## Implementation and Visual Approval Strategy

Implementation proceeds one screen at a time in real Compose:

1. Theme primitives and three fixed schemes.
2. Home, including identical palette comparison renders.
3. Edit routine.
4. GPS acquisition.
5. Active session and hold-to-stop interaction.
6. Cross-screen motion, accessibility, and final polish.

Each screen is rendered at the Pixel 9a target, visually inspected by the implementer, and shown on the emulator. Work does not advance to the next screen until the user approves the current visual result. Functional behavior, offline operation, foreground tracking, audio, and existing persistence remain intact unless this specification explicitly changes them.

## Testing and Acceptance

- Preserve all existing unit, instrumentation, location, service, audio, and routine tests.
- Add Compose tests for navigation, draft/save/discard, conditional editor sections, GPS readiness states, automatic acquisition transition, and hold-to-stop cancellation/completion.
- Add theme tests for fixed/dynamic and light/dark selection.
- Verify font scaling, TalkBack semantics, contrast, reduced motion, edge-to-edge insets, and 360×808 logical Pixel 9a layout.
- Run visual inspection on the Pixel 9a emulator for every approved screen and state.
- Repeat the existing physical-device checklist after the redesign to confirm that UI work did not affect location, audio, screen-off operation, or coexistence.

## Explicit Non-goals

- History, statistics, maps, accounts, cloud, analytics, and social features.
- Bottom navigation for speculative future destinations.
- Decorative graphs or illustrations without real data.
- HTML/CSS mockups as implementation references.
- Arbitrary numeric limits or forced time increments.
- Changes to tracking and audio behavior beyond the GPS-start and hold-to-stop decisions above.
