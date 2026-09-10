package dev.fred.pacorun.service

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SpeedEstimatorRealRunTest {
    @Test
    fun realRunReplayNeverProducesNonFiniteSpeed() {
        val fixes = loadPacoRunGpxFixture()
        assertTrue("fixture failed to load any fixes", fixes.isNotEmpty())

        val estimator = SpeedEstimator()
        val estimates = fixes.map { (position, reliable) -> estimator.accept(position, reliable) }

        estimates.forEach { assertTrue("non-finite speed in real-run replay: $it", it.isFinite()) }

        // Loose sanity bound, not a precision check (that's the spec's Python analysis,
        // section 7) -- this run's average pace was well within 4-14 km/h throughout.
        val steadyEstimates = estimates.drop(20)
        val average = steadyEstimates.average()
        assertTrue("implausible average speed for this run: $average km/h", average in 4.0..14.0)
    }

    private fun loadPacoRunGpxFixture(): List<Pair<LocationPosition, Boolean>> {
        val stream = javaClass.classLoader.getResourceAsStream("paco-run-2026-09-10-1122.gpx")
            ?: error("fixture not found on test classpath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val trkpts = document.getElementsByTagName("trkpt")
        val fixes = mutableListOf<Pair<LocationPosition, Boolean>>()

        for (i in 0 until trkpts.length) {
            val trkpt = trkpts.item(i)
            val lat = trkpt.attributes.getNamedItem("lat").nodeValue.toDouble()
            val lon = trkpt.attributes.getNamedItem("lon").nodeValue.toDouble()
            var timeIso: String? = null
            var accuracy: Float? = null
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
                                "pr:reliable" -> reliable = ext.textContent == "true"
                            }
                        }
                    }
                }
            }
            val elapsedMillis = java.time.Instant.parse(timeIso).toEpochMilli()
            fixes += LocationPosition(lat, lon, elapsedMillis, accuracy) to reliable
        }
        return fixes
    }
}
