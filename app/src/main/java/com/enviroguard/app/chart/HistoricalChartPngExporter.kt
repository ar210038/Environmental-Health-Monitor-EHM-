package com.enviroguard.app.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.github.mikephil.charting.charts.LineChart
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDate

data class ChartExportMetadata(
    val title: String,
    val period: String,
    val date: LocalDate = LocalDate.now()
) {
    val subtitle: String = "Selected period: $period"
    val suggestedFileName: String = "EHM_${fileToken(title)}_${fileToken(period)}_$date.png"

    private fun fileToken(value: String): String = value
        .split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .joinToString("") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
        .ifBlank { "Chart" }
}

/** Renders existing MPAndroidChart data without requiring storage permissions. */
object HistoricalChartPngExporter {
    private const val EXPORT_SCALE = 2f

    fun hasData(chart: LineChart): Boolean = (chart.data?.entryCount ?: 0) > 0

    fun render(
        chart: LineChart,
        metadata: ChartExportMetadata,
        backgroundColor: Int,
        titleColor: Int,
        subtitleColor: Int
    ): Result<ByteArray> = runCatching {
        require(hasData(chart)) { "No chart data is available to export." }
        require(chart.width > 0 && chart.height > 0) { "The chart is not ready to export." }

        val density = chart.resources.displayMetrics.density
        val horizontalPadding = 20f * density
        val headerHeight = 76f * density
        val bitmap = createBitmap(
            (chart.width * EXPORT_SCALE).toInt(),
            ((chart.height + headerHeight) * EXPORT_SCALE).toInt(),
            Bitmap.Config.ARGB_8888
        )
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(backgroundColor)
            canvas.scale(EXPORT_SCALE, EXPORT_SCALE)
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = titleColor
                textSize = 20f * density
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = subtitleColor
                textSize = 13f * density
            }
            val availableTitleWidth = chart.width - 2f * horizontalPadding
            if (titlePaint.measureText(metadata.title) > availableTitleWidth) {
                titlePaint.textSize = (titlePaint.textSize * availableTitleWidth /
                    titlePaint.measureText(metadata.title)).coerceAtLeast(12f * density)
            }
            canvas.drawText(metadata.title, horizontalPadding, 30f * density, titlePaint)
            canvas.drawText(metadata.subtitle, horizontalPadding, 54f * density, subtitlePaint)

            val savedViewport = Matrix(chart.viewPortHandler.matrixTouch)
            try {
                chart.fitScreen()
                chart.notifyDataSetChanged()
                canvas.withTranslation(0f, headerHeight) { chart.draw(this) }
            } finally {
                chart.viewPortHandler.refresh(savedViewport, chart, false)
            }

            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "PNG encoding failed."
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun writePng(bytes: ByteArray, openOutputStream: () -> OutputStream?): Result<Unit> = runCatching {
        require(bytes.isNotEmpty()) { "The generated PNG is empty." }
        val stream = openOutputStream() ?: error("The selected document could not be opened.")
        stream.use {
            it.write(bytes)
            it.flush()
        }
    }
}
