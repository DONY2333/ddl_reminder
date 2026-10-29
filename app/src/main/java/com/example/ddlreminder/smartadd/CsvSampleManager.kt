package com.example.ddlreminder.smartadd

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class CsvSampleManager(private val context: Context) {
    fun createShareIntent(): Intent? = runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ddl_reminder_import_sample.csv")
        file.writeText(ScheduleCsvParser.SAMPLE, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()
}
