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
    private val fileNameTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    fun fileNameFor(startedAt: Instant): String = "paco-run-${fileNameTimestamp.format(startedAt.atZone(java.time.ZoneId.systemDefault()))}.gpx"

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
