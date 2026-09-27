package com.enviroguard.app

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import com.enviroguard.app.chart.ChartExportMetadata
import com.enviroguard.app.chart.HistoricalChartPngExporter
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoricalChartExportUnitTest {
    @Test
    fun metadataIncludesSelectedPeriodAndDescriptiveDateFilename() {
        val metadata = ChartExportMetadata(
            title = "Heat Index",
            period = "Day",
            date = LocalDate.of(2026, 9, 28)
        )

        assertEquals("Selected period: Day", metadata.subtitle)
        assertEquals("EHM_HeatIndex_Day_2026-09-28.png", metadata.suggestedFileName)
    }

    @Test
    fun emptyChartCannotGeneratePng() {
        val chart = laidOutChart()

        assertFalse(HistoricalChartPngExporter.hasData(chart))
        assertTrue(render(chart).isFailure)
    }

    @Test
    fun populatedChartGeneratesHighResolutionPng() {
        val chart = laidOutChart().apply {
            data = LineData(
                LineDataSet(
                    listOf(Entry(0f, 22f), Entry(60f, 24f), Entry(120f, 23f)),
                    "Temperature"
                )
            )
            notifyDataSetChanged()
        }

        val png = render(chart).getOrThrow()
        assertArrayEquals(
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
            png.copyOfRange(0, 8)
        )
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(1_600, bitmap.width)
        assertTrue(bitmap.height > 800)
        bitmap.recycle()
    }

    @Test
    fun pngWriterReportsSuccessNullDestinationAndIoFailure() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val destination = ByteArrayOutputStream()
        assertTrue(HistoricalChartPngExporter.writePng(expected) { destination }.isSuccess)
        assertArrayEquals(expected, destination.toByteArray())

        assertTrue(HistoricalChartPngExporter.writePng(expected) { null }.isFailure)
        assertTrue(
            HistoricalChartPngExporter.writePng(expected) {
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException("simulated write failure")
                }
            }.isFailure
        )
    }

    private fun laidOutChart(): LineChart = LineChart(RuntimeEnvironment.getApplication()).apply {
        measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 800, 400)
    }

    private fun render(chart: LineChart) = HistoricalChartPngExporter.render(
        chart = chart,
        metadata = ChartExportMetadata("Temperature", "Day", LocalDate.of(2026, 9, 28)),
        backgroundColor = Color.WHITE,
        titleColor = Color.BLACK,
        subtitleColor = Color.DKGRAY
    )
}
