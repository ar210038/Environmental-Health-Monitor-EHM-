package com.enviroguard.app.dataset

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import java.io.File
import java.time.LocalDate

object DatasetExportManager {
    fun createShareIntent(context: Context, readings: List<SensorReadingEntity>): Intent {
        val exportDirectory = File(context.cacheDir, "dataset_exports").apply { mkdirs() }
        val file = File(exportDirectory, "EnviroGuard_Dataset_${LocalDate.now()}.csv")
        file.writeText(DatasetCsvExporter.generate(readings), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
