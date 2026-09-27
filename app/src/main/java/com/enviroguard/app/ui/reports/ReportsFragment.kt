package com.enviroguard.app.ui.reports

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.chart.ChartExportMetadata
import com.enviroguard.app.chart.HistoricalChartPngExporter
import com.enviroguard.app.databinding.FragmentReportsBinding
import com.enviroguard.app.utils.ViewModelFactory
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import androidx.core.content.ContextCompat
import com.enviroguard.app.forecast.ForecastPresentationFactory
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportsFragment : Fragment() {
    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ReportsViewModel
    private var pendingChartPng: ByteArray? = null
    private val createChartDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> savePendingChart(uri) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View { _binding = FragmentReportsBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        val app = requireActivity().application as EnviroGuardApp
        viewModel = ViewModelProvider(
            this,
            ViewModelFactory(repository = app.repository, forecastService = app.forecastService)
        )[ReportsViewModel::class.java]
        binding.btnDownloadConditionChart.setOnClickListener { prepareChartExport() }
        binding.conditionChart.apply {
            description.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 3f
            axisLeft.granularity = 1f
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)
            axisLeft.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float) = listOf("GOOD", "MODERATE", "POOR", "CRITICAL").getOrElse(value.toInt()) { "" }
            }
            legend.isEnabled = false
        }
        val tabs = listOf(binding.tabDaily, binding.tabWeekly, binding.tabMonthly)
        val periodTitles = listOf("Today’s environment", "Last 7 days", "Last 30 days")
        tabs.forEachIndexed { index, tab -> tab.setOnClickListener { tabs.forEach { it.setBackgroundResource(0); it.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)) }; tab.setBackgroundResource(R.drawable.bg_tab_active); tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_primary)); binding.tvEnvironmentPeriodTitle.text = periodTitles[index]; viewModel.selectPeriod(index) } }
        viewModel.thermalDuration.observe(viewLifecycleOwner) { binding.tvThermalDuration.text = it }
        viewModel.airDuration.observe(viewLifecycleOwner) { binding.tvAirDuration.text = it }
        viewModel.noiseDuration.observe(viewLifecycleOwner) { binding.tvNoiseDuration.text = it }
        viewModel.dominantContributor.observe(viewLifecycleOwner) { binding.tvDominantContributor.text = it }
        viewModel.historicalPattern.observe(viewLifecycleOwner) { binding.tvHistoricalPattern.text = it }
        viewModel.forecastState.observe(viewLifecycleOwner) { state ->
            val presentation = ForecastPresentationFactory.create(state)
            binding.tvForecastTitle.text = presentation.title
            binding.tvForecastState.text = presentation.detail
            binding.forecastProgress.visibility = if (presentation.showProgress) View.VISIBLE else View.GONE
        }
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.conditionChart.visibility = if (empty) View.GONE else View.VISIBLE
            binding.tvTrendsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            setDownloadEnabled(!empty)
        }
        viewModel.chartPoints.observe(viewLifecycleOwner) { points ->
            if (points.isEmpty()) { binding.conditionChart.clear(); return@observe }
            val base = points.first().first
            val set = LineDataSet(points.map { Entry((it.first - base) / 1000f, it.second) }, "Environmental condition").apply { color = ContextCompat.getColor(requireContext(), R.color.ehm_primary); lineWidth = 2.5f; setDrawCircles(false); setDrawValues(false); mode = LineDataSet.Mode.STEPPED }
            binding.conditionChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() { override fun getFormattedValue(value: Float) = viewModel.getXAxisLabel(base + (value * 1000).toLong(), viewModel.selectedPeriod.value ?: 0) }
            binding.conditionChart.data = LineData(set); binding.conditionChart.invalidate()
        }
    }
    private fun prepareChartExport() {
        if (!HistoricalChartPngExporter.hasData(binding.conditionChart)) {
            Toast.makeText(requireContext(), R.string.chart_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val period = listOf("Day", "Week", "Month").getOrElse(viewModel.selectedPeriod.value ?: 0) { "Period" }
        val metadata = ChartExportMetadata("Environmental condition timeline", period)
        HistoricalChartPngExporter.render(
            chart = binding.conditionChart,
            metadata = metadata,
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.ehm_surface),
            titleColor = ContextCompat.getColor(requireContext(), R.color.ehm_on_surface),
            subtitleColor = ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)
        ).onSuccess { png ->
            pendingChartPng = png
            createChartDocument.launch(metadata.suggestedFileName)
        }.onFailure {
            Toast.makeText(requireContext(), R.string.chart_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    private fun savePendingChart(uri: Uri?) {
        val png = pendingChartPng.also { pendingChartPng = null } ?: return
        if (uri == null) return
        val appContext = context?.applicationContext ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                HistoricalChartPngExporter.writePng(png) {
                    appContext.contentResolver.openOutputStream(uri, "w")
                }.isSuccess
            }
            Toast.makeText(
                appContext,
                if (saved) R.string.chart_export_success else R.string.chart_export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    private fun setDownloadEnabled(enabled: Boolean) {
        binding.btnDownloadConditionChart.isEnabled = enabled
        binding.btnDownloadConditionChart.alpha = if (enabled) 1f else 0.38f
    }
    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.selectPeriod(viewModel.selectedPeriod.value ?: 0) }
    override fun onDestroyView() { pendingChartPng = null; super.onDestroyView(); _binding = null }
}
