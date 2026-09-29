package dev.fred.pacorun.export

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timeEpochMillis: Long,
    val speedMps: Float?,
    val horizontalAccuracyMeters: Float?,
    val speedAccuracyMps: Float?,
    val reliable: Boolean,
    // Diagnostic-only: SpeedEstimator's own speed output for this fix. Null when the fix was
    // completely unreliable (the engine was never called for it) -- see RunSessionService.
    val speedEstimatorKmh: Double?,
    // SystemClock.elapsedRealtime()-based, monotone timestamp of this exact fix -- the join key
    // against the new SessionEvent log (which also keys on elapsedRealtimeMillis, not wall
    // clock). See session-event-logging-design.md Decision 4.
    val elapsedRealtimeMillis: Long,
)

// Standard GPX 1.1 trkpt/time so any GPX viewer (or Strava, for a side-by-side comparison)
// reads the track; a `pr:` extension namespace carries the extra diagnostic fields (which raw
// fixes were actually trusted, their reported accuracy) that a normal export wouldn't have.
object GpxWriter {
    // Instant.toString() drops the fractional-seconds part when it's exactly zero, giving
    // inconsistent timestamp widths across points -- pin it to always-3-decimals instead.
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun build(points: List<TrackPoint>, activityName: String): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine(
            "<gpx version=\"1.1\" creator=\"Paco Run\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:pr=\"https://pacorun.fred.dev/gpx-extensions/1\">",
        )
        appendLine("  <trk>")
        appendLine("    <name>${activityName.escapeXml()}</name>")
        appendLine("    <trkseg>")
        points.forEach { point -> appendTrackPoint(point) }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        append("</gpx>")
    }

    private fun StringBuilder.appendTrackPoint(point: TrackPoint) {
        appendLine("      <trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">")
        appendLine("        <time>${timeFormatter.format(Instant.ofEpochMilli(point.timeEpochMillis))}</time>")
        appendLine("        <extensions>")
        point.speedMps?.let { appendLine("          <pr:speedMps>$it</pr:speedMps>") }
        point.horizontalAccuracyMeters?.let { appendLine("          <pr:horizontalAccuracyMeters>$it</pr:horizontalAccuracyMeters>") }
        point.speedAccuracyMps?.let { appendLine("          <pr:speedAccuracyMps>$it</pr:speedAccuracyMps>") }
        appendLine("          <pr:reliable>${point.reliable}</pr:reliable>")
        point.speedEstimatorKmh?.let { appendLine("          <pr:speedEstimatorKmh>$it</pr:speedEstimatorKmh>") }
        appendLine("          <pr:elapsedRealtimeMillis>${point.elapsedRealtimeMillis}</pr:elapsedRealtimeMillis>")
        appendLine("        </extensions>")
        appendLine("      </trkpt>")
    }

    private fun String.escapeXml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
