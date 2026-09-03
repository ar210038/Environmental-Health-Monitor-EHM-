package com.enviroguard.app.ui.reports

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.databinding.FragmentReportsBinding
import com.enviroguard.app.utils.ViewModelFactory
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import androidx.core.content.ContextCompat
import com.enviroguard.app.forecast.ForecastPresentationFactory

class ReportsFragment : Fragment() {
    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ReportsViewModel
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View { _binding = FragmentReportsBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        val app = requireActivity().application as EnviroGuardApp
        viewModel = ViewModelProvider(
            this,
            ViewModelFactory(repository = app.repository, forecastService = app.forecastService)
        )[ReportsViewModel::class.java]
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
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty -> binding.conditionChart.visibility = if (empty) View.GONE else View.VISIBLE; binding.tvTrendsEmpty.visibility = if (empty) View.VISIBLE else View.GONE }
        viewModel.chartPoints.observe(viewLifecycleOwner) { points ->
            if (points.isEmpty()) { binding.conditionChart.clear(); return@observe }
            val base = points.first().first
            val set = LineDataSet(points.map { Entry((it.first - base) / 1000f, it.second) }, "Environmental condition").apply { color = ContextCompat.getColor(requireContext(), R.color.ehm_primary); lineWidth = 2.5f; setDrawCircles(false); setDrawValues(false); mode = LineDataSet.Mode.STEPPED }
            binding.conditionChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() { override fun getFormattedValue(value: Float) = viewModel.getXAxisLabel(base + (value * 1000).toLong(), viewModel.selectedPeriod.value ?: 0) }
            binding.conditionChart.data = LineData(set); binding.conditionChart.invalidate()
        }
    }
    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.selectPeriod(viewModel.selectedPeriod.value ?: 0) }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
