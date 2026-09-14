package dev.fred.pacorun.eventlog

// Hand-written JSON, matching GpxWriter's own precedent for XML (spec Decision 3): this project
// has zero JSON serialization dependency, and JSON is structurally simpler than XML to write by
// hand (no tag matching). JsonObjectBuilder below exists only to avoid retyping comma-joining
// logic across the 12 functions below -- it is not a general-purpose/reflective serializer; the
// exhaustive `when` in toJsonLine() is still what the compiler checks on every new event type.
object SessionEventWriter {
    fun toJsonLine(event: SessionEvent): String = when (event) {
        is SessionStarted -> sessionStarted(event)
        is LocationFixEvent -> locationFixEvent(event)
        is GpsSilentGapEvent -> gpsSilentGapEvent(event)
        is AudioCueEmittedEvent -> audioCueEmittedEvent(event)
        is RoutinePhaseChangedEvent -> routinePhaseChangedEvent(event)
        is PaceStateConfirmedEvent -> paceStateConfirmedEvent(event)
        is PaceCandidateStartedEvent -> paceCandidateStartedEvent(event)
        is PaceCandidateAbandonedEvent -> paceCandidateAbandonedEvent(event)
        is SpeedChainBrokenEvent -> speedChainBrokenEvent(event)
        is DistanceChainBrokenEvent -> distanceChainBrokenEvent(event)
        is AppLifecycleChangedEvent -> appLifecycleChangedEvent(event)
        is DeviceHealthSampledEvent -> deviceHealthSampledEvent(event)
    }

    private fun sessionStarted(event: SessionStarted) = json {
        string("type", "SessionStarted")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        string("deviceModel", event.deviceModel)
        string("osVersionRelease", event.osVersionRelease)
        int("osApiLevel", event.osApiLevel)
        string("appGitCommitHash", event.appGitCommitHash)
        raw("preferences", runPreferences(event.preferences))
        raw("sensors", sensorAvailability(event.sensors))
        enum("networkTypeAtStart", event.networkTypeAtStart)
    }

    private fun runPreferences(preferences: dev.fred.pacorun.settings.RunPreferences) = json {
        enum("mode", preferences.mode)
        raw("constantRange", speedRange(preferences.constantRange))
        raw("runRange", speedRange(preferences.runRange))
        boolean("walkUsesConstantRange", preferences.walkUsesConstantRange)
        raw("walkRange", speedRange(preferences.walkRange))
        double("initialConstantKm", preferences.initialConstantKm.toDouble())
        int("runSeconds", preferences.runSeconds)
        int("walkSeconds", preferences.walkSeconds)
        boolean("routineUnlimited", preferences.routineUnlimited)
        double("routineKm", preferences.routineKm.toDouble())
        int("confirmationSamples", preferences.confirmationSamples)
        long("repeatMillis", preferences.repeatMillis)
        boolean("gpsCuesEnabled", preferences.gpsCuesEnabled)
        double("cueVolume", preferences.cueVolume.toDouble())
        boolean("dynamicColor", preferences.dynamicColor)
    }

    private fun speedRange(range: dev.fred.pacorun.settings.SpeedRange) = json {
        double("minKmh", range.minKmh.toDouble())
        double("maxKmh", range.maxKmh.toDouble())
    }

    private fun sensorAvailability(sensors: SensorAvailability) = json {
        boolean("hasBarometer", sensors.hasBarometer)
        boolean("hasMagnetometer", sensors.hasMagnetometer)
        boolean("hasAccelerometer", sensors.hasAccelerometer)
        boolean("hasGyroscope", sensors.hasGyroscope)
        boolean("hasStepCounter", sensors.hasStepCounter)
        boolean("hasStepDetector", sensors.hasStepDetector)
    }

    private fun locationFixEvent(event: LocationFixEvent) = json {
        string("type", "LocationFixEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        int("sequenceNumber", event.sequenceNumber)
        long("elapsedSinceStartMillis", event.elapsedSinceStartMillis)
        stringOrNull("provider", event.provider)
        boolean("reliable", event.reliable)
        stringArray("rejectionReasons", event.rejectionReasons.map { it.name })
        intOrNull("satellitesUsedInFix", event.satellitesUsedInFix)
        floatOrNull("satelliteCn0MinDbHz", event.satelliteCn0MinDbHz)
        floatOrNull("satelliteCn0AvgDbHz", event.satelliteCn0AvgDbHz)
        floatOrNull("satelliteCn0MaxDbHz", event.satelliteCn0MaxDbHz)
        enum("qualityTier", event.qualityTier)
        raw("speedEstimatorDiagnostics", event.speedEstimatorDiagnostics?.let(::speedEstimatorDiagnostics))
    }

    private fun speedEstimatorDiagnostics(diagnostics: SpeedEstimatorDiagnostics) = json {
        enum("processNoiseRegime", diagnostics.processNoiseRegime)
        boolean("accuracyGateEngaged", diagnostics.accuracyGateEngaged)
        double("innovationEmaMagnitude", diagnostics.innovationEmaMagnitude)
    }

    private fun gpsSilentGapEvent(event: GpsSilentGapEvent) = json {
        string("type", "GpsSilentGapEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        long("gapMillis", event.gapMillis)
    }

    private fun audioCueEmittedEvent(event: AudioCueEmittedEvent) = json {
        string("type", "AudioCueEmittedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        string("cueType", event.cueType)
        double("cumulativeDistanceMeters", event.cumulativeDistanceMeters)
        floatOrNull("speedKmh", event.speedKmh)
    }

    private fun routinePhaseChangedEvent(event: RoutinePhaseChangedEvent) = json {
        string("type", "RoutinePhaseChangedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("fromPhase", event.fromPhase)
        enum("toPhase", event.toPhase)
        double("cumulativeDistanceMeters", event.cumulativeDistanceMeters)
    }

    private fun paceStateConfirmedEvent(event: PaceStateConfirmedEvent) = json {
        string("type", "PaceStateConfirmedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enumOrNull("fromState", event.fromState)
        enum("toState", event.toState)
        floatOrNull("speedKmh", event.speedKmh)
        boolean("producedAudibleCue", event.producedAudibleCue)
    }

    private fun paceCandidateStartedEvent(event: PaceCandidateStartedEvent) = json {
        string("type", "PaceCandidateStartedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("candidateState", event.candidateState)
    }

    private fun paceCandidateAbandonedEvent(event: PaceCandidateAbandonedEvent) = json {
        string("type", "PaceCandidateAbandonedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("candidateState", event.candidateState)
        int("samplesAccumulated", event.samplesAccumulated)
        enum("reason", event.reason)
    }

    private fun speedChainBrokenEvent(event: SpeedChainBrokenEvent) = json {
        string("type", "SpeedChainBrokenEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("reason", event.reason)
    }

    private fun distanceChainBrokenEvent(event: DistanceChainBrokenEvent) = json {
        string("type", "DistanceChainBrokenEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("reason", event.reason)
    }

    private fun appLifecycleChangedEvent(event: AppLifecycleChangedEvent) = json {
        string("type", "AppLifecycleChangedEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        enum("transition", event.transition)
    }

    private fun deviceHealthSampledEvent(event: DeviceHealthSampledEvent) = json {
        string("type", "DeviceHealthSampledEvent")
        long("elapsedRealtimeMillis", event.elapsedRealtimeMillis)
        intOrNull("batteryPercent", event.batteryPercent)
        enum("thermalStatus", event.thermalStatus)
    }

    private fun json(build: JsonObjectBuilder.() -> Unit): String = JsonObjectBuilder().apply(build).build()
}

// NaN/Infinity are not valid JSON tokens -- a hand-written serializer that doesn't guard for
// them emits an unparseable line. SpeedEstimatorDiagnostics.innovationEmaMagnitude can
// legitimately be non-finite (SpeedEstimator.accept() itself guards speed.isFinite() before
// returning, for the same reason) -- "no valid measurement" becomes JSON null, same discipline
// as escapeJson() for strings, applied to every Double/Float this writer serializes (Float
// included, extending the spec's Double-only guard to every floating-point field actually used).
internal fun jsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "null"
internal fun jsonNumber(value: Float): String = if (value.isFinite()) value.toString() else "null"

internal fun String.escapeJson(): String = buildString {
    this@escapeJson.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}

private class JsonObjectBuilder {
    private val fields = mutableListOf<String>()

    fun string(name: String, value: String) = raw(name, "\"${value.escapeJson()}\"")
    fun stringOrNull(name: String, value: String?) = raw(name, value?.let { "\"${it.escapeJson()}\"" })
    fun long(name: String, value: Long) = raw(name, value.toString())
    fun int(name: String, value: Int) = raw(name, value.toString())
    fun intOrNull(name: String, value: Int?) = raw(name, value?.toString())
    fun boolean(name: String, value: Boolean) = raw(name, value.toString())
    fun double(name: String, value: Double) = raw(name, jsonNumber(value))
    fun floatOrNull(name: String, value: Float?) = raw(name, value?.let { jsonNumber(it) })
    fun enum(name: String, value: Enum<*>) = raw(name, "\"${value.name}\"")
    fun enumOrNull(name: String, value: Enum<*>?) = raw(name, value?.let { "\"${it.name}\"" })
    fun stringArray(name: String, values: List<String>) =
        raw(name, values.joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" })

    fun raw(name: String, jsonValue: String?) {
        fields += "\"$name\":${jsonValue ?: "null"}"
    }

    fun build(): String = fields.joinToString(",", "{", "}")
}
