package dev.fred.pacorun.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SessionEventWriter
import java.io.OutputStream

// Android-level sink for SessionEvent: opens one MediaStore Downloads file per session and
// appends one JSON Lines row per event as it happens (Decision 8 -- incremental, not buffered
// to a single end-of-session write, so a crash mid-session still leaves a readable partial log).
// Every write is wrapped in its own try/catch, matching GpxExporter's best-effort discipline --
// a full disk or restrictive OEM storage behavior must never crash the app over a diagnostic log.
class SessionEventLogger(private val context: Context) {
    private var outputStream: OutputStream? = null

    fun start(fileName: String) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/jsonl")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            outputStream = resolver.openOutputStream(uri, "w")
        } catch (_: Exception) {
        }
    }

    fun log(event: SessionEvent) {
        try {
            outputStream?.write((SessionEventWriter.toJsonLine(event) + "\n").toByteArray(Charsets.UTF_8))
            // flush() is a defensive no-op for the current MediaStore stream type -- write()
            // already reaches the OS immediately via the underlying ParcelFileDescriptor, so
            // durability comes from the write above, not from here. Kept in case a future stream
            // implementation buffers internally.
            outputStream?.flush()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        outputStream = null
    }
}
