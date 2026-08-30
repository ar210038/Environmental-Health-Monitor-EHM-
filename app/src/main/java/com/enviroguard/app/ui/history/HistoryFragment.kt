package com.enviroguard.app.ui.history

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.databinding.FragmentHistoryBinding
import com.enviroguard.app.utils.ViewModelFactory
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class HistoryFragment : Fragment() {
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HistoryViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        viewModel = ViewModelProvider(this, ViewModelFactory((requireActivity().application as EnviroGuardApp).repository))[HistoryViewModel::class.java]
        setupChart()
        setupControls()
        observeViewModel()
    }

    private fun setupChart() = binding.historyChart.apply {
        description.isEnabled = false
        setTouchEnabled(true)
        isDragEnabled = true
        setScaleEnabled(true)
        setPinchZoom(true)
        setDrawGridBackground(false)
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)
        axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)
        axisRight.isEnabled = false
        legend.isEnabled = false
    }

    private fun setupControls() {
        val metricIds = listOf(R.id.chipTemperature, R.id.chipHumidity, R.id.chipHeatIndex, R.id.chipTvoc, R.id.chipEco2, R.id.chipNoise)
        binding.metricChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            checkedIds.firstOrNull()?.let { id -> metricIds.indexOf(id).takeIf { it >= 0 }?.let(viewModel::selectSensor) }
        }
        val tabs = listOf(binding.rangeHour, binding.rangeDay, binding.rangeWeek)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                tabs.forEach { item -> item.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant)); item.setBackgroundResource(0); item.setTypeface(null, Typeface.NORMAL) }
                tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_primary))
                tab.setBackgroundResource(R.drawable.bg_tab_active)
                tab.setTypeface(null, Typeface.BOLD)
                viewModel.selectRange(index)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.chartTitle.observe(viewLifecycleOwner) { binding.tvHistoryChartTitle.text = it }
        viewModel.minValue.observe(viewLifecycleOwner) { binding.tvMinValue.text = it }
        viewModel.avgValue.observe(viewLifecycleOwner) { binding.tvAvgValue.text = it }
        viewModel.maxValue.observe(viewLifecycleOwner) { binding.tvMaxValue.text = it }
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.historyChart.visibility = if (empty) View.GONE else View.VISIBLE
            binding.tvHistoryEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            if (empty) binding.historyChart.clear()
        }
        viewModel.chartPoints.observe(viewLifecycleOwner) { points ->
            if (points.isEmpty()) return@observe
            val range = viewModel.selectedRange.value ?: 0
            val colorResources = listOf(R.color.ehm_good, R.color.ehm_moderate, R.color.ehm_poor, R.color.ehm_poor, R.color.ehm_primary, R.color.ehm_primary)
            val color = ContextCompat.getColor(requireContext(), colorResources[viewModel.selectedSensor.value ?: 0])
            val base = points.first().first
            val dataSet = LineDataSet(points.map { Entry((it.first - base) / 1_000f, it.second) }, "").apply {
                this.color = color
                lineWidth = 2.5f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                fillAlpha = 28
                fillColor = color
                setDrawFilled(true)
            }
            binding.historyChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float) = viewModel.getXAxisLabel(base + (value * 1_000f).toLong(), range)
            }
            binding.historyChart.data = LineData(dataSet)
            binding.historyChart.invalidate()
        }
    }

    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.refreshForActiveDevice() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
