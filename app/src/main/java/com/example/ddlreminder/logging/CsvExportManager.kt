package com.example.ddlreminder.logging

import com.example.ddlreminder.diagnostics.recoverable

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

class CsvExportManager(
    private val context: Context,
    private val eventLogger: EventLogger,
) {
    suspend fun createShareIntent(): Intent? = recoverable {
        val logs = eventLogger.getAllLogs()
        val exportDirectory = File(context.cacheDir, "event_exports").apply { mkdirs() }
        val file = File(exportDirectory, "ddl_reminder_events_${LocalDate.now()}.csv")
        file.writeText(EventCsvExporter.toCsv(logs), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("DDL Reminder events", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()
}
