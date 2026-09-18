package dev.fred.pacorun.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxWriterTest {
    @Test
    fun emptyTrackProducesAValidGpxWithNoTrackpoints() {
        val gpx = GpxWriter.build(emptyList(), activityName = "Paco Run")

        assertTrue(gpx.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("<name>Paco Run</name>"))
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertEquals(0, Regex("<trkpt").findAll(gpx).count())
    }

    @Test
    fun eachPointBecomesATrkptWithLatLonAndIsoTime() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 45.4642,
                    longitude = 9.1900,
                    timeEpochMillis = 1_757_000_000_000L,
                    speedMps = 2.5f,
                    horizontalAccuracyMeters = 4.5f,
                    speedAccuracyMps = 0.2f,
                    reliable = true,
                    speedEstimatorKmh = 9.0,
                    elapsedRealtimeMillis = 0L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(gpx.contains("<trkpt lat=\"45.4642\" lon=\"9.19"))
        assertTrue(gpx.contains("<time>2025-09-04T15:33:20.000Z</time>"))
        assertEquals(1, Regex("<trkpt").findAll(gpx).count())
    }

    @Test
    fun pointExtensionsCarrySpeedAccuracyAndReliability() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = 3.1f,
                    horizontalAccuracyMeters = 12.5f,
                    speedAccuracyMps = 0.3f,
                    reliable = false,
                    speedEstimatorKmh = 11.2,
                    elapsedRealtimeMillis = 0L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(gpx.contains("<pr:speedMps>3.1</pr:speedMps>"))
        assertTrue(gpx.contains("<pr:horizontalAccuracyMeters>12.5</pr:horizontalAccuracyMeters>"))
        assertTrue(gpx.contains("<pr:speedAccuracyMps>0.3</pr:speedAccuracyMps>"))
        assertTrue(gpx.contains("<pr:reliable>false</pr:reliable>"))
    }

    @Test
    fun pointExtensionsCarrySpeedEstimatorKmhWhenPresent() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = null,
                    horizontalAccuracyMeters = null,
                    speedAccuracyMps = null,
                    reliable = true,
                    speedEstimatorKmh = 7.35,
                    elapsedRealtimeMillis = 0L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(gpx.contains("<pr:speedEstimatorKmh>7.35</pr:speedEstimatorKmh>"))
    }

    @Test
    fun speedEstimatorKmhOmittedWhenNull() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = null,
                    horizontalAccuracyMeters = null,
                    speedAccuracyMps = null,
                    reliable = false,
                    speedEstimatorKmh = null,
                    elapsedRealtimeMillis = 0L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(!gpx.contains("<pr:speedEstimatorKmh>"))
    }

    @Test
    fun missingOptionalFieldsAreOmittedNotRenderedAsNull() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = null,
                    horizontalAccuracyMeters = null,
                    speedAccuracyMps = null,
                    reliable = true,
                    speedEstimatorKmh = 0.0,
                    elapsedRealtimeMillis = 0L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(!gpx.contains("null"))
        assertTrue(!gpx.contains("<pr:speedMps>"))
    }

    @Test
    fun multiplePointsPreserveOrder() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(0.0, 0.0, 0L, null, null, null, true, 0.0, 0L),
                TrackPoint(0.001, 0.001, 1_000L, null, null, null, true, 0.0, 1_000L),
                TrackPoint(0.002, 0.002, 2_000L, null, null, null, true, 0.0, 2_000L),
            ),
            activityName = "Paco Run",
        )

        val order = Regex("lat=\"([0-9.]+)\"").findAll(gpx).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0.0", "0.001", "0.002"), order)
    }

    @Test
    fun pointExtensionsCarryElapsedRealtimeMillisForCorrelationWithTheEventsLog() {
        val gpx = GpxWriter.build(
            listOf(
                TrackPoint(
                    latitude = 0.0,
                    longitude = 0.0,
                    timeEpochMillis = 0L,
                    speedMps = null,
                    horizontalAccuracyMeters = null,
                    speedAccuracyMps = null,
                    reliable = true,
                    speedEstimatorKmh = null,
                    elapsedRealtimeMillis = 123_456L,
                ),
            ),
            activityName = "Paco Run",
        )

        assertTrue(gpx.contains("<pr:elapsedRealtimeMillis>123456</pr:elapsedRealtimeMillis>"))
    }
}
