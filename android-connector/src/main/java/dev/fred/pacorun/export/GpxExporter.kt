package dev.fred.pacorun.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.time.Instant
import java.time.format.DateTimeFormatter

// Writes into the shared Downloads collection (not app-private storage) so the file shows up
// in a Files app / file manager for the user to send elsewhere -- exactly the point of this
// export, since it's meant to leave the device for a comparison against another data source.
object GpxExporter {
    private val fileNameTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun fileNameFor(startedAt: Instant): String = "paco-run-${timestampFor(startedAt)}.gpx"

    // Same timestamp basis as fileNameFor(), so the two files stay associable by name (in
    // addition to elapsedRealtimeMillis) -- session-event-logging-design.md Decision 8.
    fun eventLogFileNameFor(startedAt: Instant): String = "paco-run-${timestampFor(startedAt)}-events.jsonl"

    private fun timestampFor(startedAt: Instant) =
        fileNameTimestamp.format(startedAt.atZone(java.time.ZoneId.systemDefault()))

    fun writeToDownloads(context: Context, fileName: String, contents: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(contents.toByteArray(Charsets.UTF_8)) } ?: return false
        return true
    }
}
