package dev.fred.pacorun.export

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpxExporterTest {
    @Test
    fun writesAReadableFileToDownloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "paco-run-export-test-${System.currentTimeMillis()}.gpx"
        val contents = GpxWriter.build(
            listOf(TrackPoint(45.4642, 9.1900, 0L, 2.5f, 4f, .2f, true)),
            activityName = "Test",
        )

        val written = GpxExporter.writeToDownloads(context, fileName, contents)
        assertTrue(written)

        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, arrayOf(fileName), null).use { cursor ->
            assertTrue("expected the written file to be findable by name", cursor != null && cursor.moveToFirst())
            val id = cursor!!.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            val readBack = resolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
            assertEquals(contents, readBack)
            resolver.delete(uri, null, null)
        }
    }
}
