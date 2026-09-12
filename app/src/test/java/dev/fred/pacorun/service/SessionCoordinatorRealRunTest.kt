package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.settings.RunPreferences
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SessionCoordinatorRealRunTest {
    @Test
    fun realRunReplayThroughFullCoordinatorNeverCrashesAndProducesPlausibleOutput() {
        val fixes = loadPacoRunGpxFixture()
        assertTrue("fixture failed to load any fixes", fixes.isNotEmpty())

        val preferences = RunPreferences()
        val published = mutableListOf<RunSessionState>()
        val pending = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences)),
            paceEvaluator = PaceEvaluatorAdapter(
                PaceEvaluator(
                    PaceConfig(
                        minKmh = preferences.constantRange.minKmh,
                        maxKmh = preferences.constantRange.maxKmh,
                        confirmationSamples = preferences.confirmationSamples,
                        repeatMillis = preferences.repeatMillis,
                        gpsCuesEnabled = preferences.gpsCuesEnabled,
                    ),
                ),
            ),
            onCues = { _, _ -> },
            publish = published::add,
        )

        fixes.forEach { (sample, position) -> pending.tick(sample.elapsedRealtimeMillis, sample, position) }

        assertTrue("coordinator never activated on this real run", pending.isActivated)
        val runningStates = published.filterIsInstance<RunSessionState.Running>()
        assertTrue("no Running state was ever published", runningStates.isNotEmpty())
        runningStates.forEach { state ->
            state.speedKmh?.let { assertTrue("non-finite speedKmh in published state: $it", it.isFinite()) }
        }
        val finalDistance = runningStates.last().totalDistanceMeters
        // Loose sanity bound (not a precision check -- that's SpeedEstimatorRealRunTest.kt and
        // the spec's own Python analysis): this ~35-40 minute run plausibly covers somewhere
        // between 3 and 10 km.
        assertTrue("implausible total distance for this run: $finalDistance m", finalDistance in 3_000.0..10_000.0)
    }

    private fun loadPacoRunGpxFixture(): List<Pair<LocationSample, LocationPosition>> {
        val stream = javaClass.classLoader?.getResourceAsStream("paco-run-2026-09-10-1122.gpx")
            ?: error("fixture not found on test classpath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val trkpts = document.getElementsByTagName("trkpt")
        val fixes = mutableListOf<Pair<LocationSample, LocationPosition>>()

        for (i in 0 until trkpts.length) {
            val trkpt = trkpts.item(i)
            val lat = trkpt.attributes.getNamedItem("lat").nodeValue.toDouble()
            val lon = trkpt.attributes.getNamedItem("lon").nodeValue.toDouble()
            var timeIso: String? = null
            var accuracy: Float? = null
            var speedMps: Float? = null
            var reliable = true
            val children = trkpt.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                when (child.nodeName) {
                    "time" -> timeIso = child.textContent
                    "extensions" -> {
                        val extChildren = child.childNodes
                        for (k in 0 until extChildren.length) {
                            val ext = extChildren.item(k)
                            when (ext.nodeName) {
                                "pr:horizontalAccuracyMeters" -> accuracy = ext.textContent.toFloatOrNull()
                                "pr:speedMps" -> speedMps = ext.textContent.toFloatOrNull()
                                "pr:reliable" -> reliable = ext.textContent == "true"
                            }
                        }
                    }
                }
            }
            val elapsedMillis = java.time.Instant.parse(timeIso).toEpochMilli()
            val sample = LocationSample(
                speedKmh = (speedMps ?: 0f) * 3.6f,
                speedAccuracyKmh = null,
                reliable = reliable,
                elapsedRealtimeMillis = elapsedMillis,
            )
            val position = LocationPosition(lat, lon, elapsedMillis, accuracy)
            fixes += sample to position
        }
        return fixes
    }
}
