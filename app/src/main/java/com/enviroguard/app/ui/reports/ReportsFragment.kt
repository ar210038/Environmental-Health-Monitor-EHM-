package com.enviroguard.app.ui.reports

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.databinding.FragmentReportsBinding
import com.enviroguard.app.utils.ViewModelFactory
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ReportsViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = ViewModelFactory(
            (requireActivity().application as EnviroGuardApp).repository
        )
        viewModel = ViewModelProvider(this, factory)[ReportsViewModel::class.java]

        setupChart()
        setupTabs()
        observeViewModel()
    }

    private fun setupChart() {
        binding.ersChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 3f
            legend.isEnabled = false
        }
    }

    private fun setupTabs() {
        val tabs = listOf(binding.tabDaily, binding.tabWeekly, binding.tabMonthly)
        val periods = listOf("Environmental Condition — Today", "Environmental Condition — Last 7 Days", "Environmental Condition — Last 30 Days")

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                // Update tab styles
                tabs.forEach { t ->
                    t.setTextColor(Color.parseColor("#9AA5A3"))
                    t.setBackgroundResource(0)
                    t.textSize = 13f
                    t.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
                tab.setTextColor(Color.parseColor("#1A6B8A"))
                tab.setBackgroundResource(com.enviroguard.app.R.drawable.bg_tab_active)
                tab.textSize = 13f
                tab.setTypeface(null, android.graphics.Typeface.BOLD)

                binding.tvChartTitle.text = periods[index]
                viewModel.selectPeriod(index)
            }
        }
    }

    private fun observeViewModel() {

        viewModel.chartTitle.observe(viewLifecycleOwner) {
            binding.tvChartTitle.text = it
        }

        viewModel.avgErs.observe(viewLifecycleOwner) {
            binding.tvAvgErs.text = it
        }

        viewModel.peakTime.observe(viewLifecycleOwner) {
            binding.tvPeakTime.text = it
        }

        viewModel.safestTime.observe(viewLifecycleOwner) {
            binding.tvSafestTime.text = it
        }

        viewModel.highRiskDuration.observe(viewLifecycleOwner) {
            binding.tvHighRiskDuration.text = it
        }

        viewModel.patternTitle.observe(viewLifecycleOwner) {
            binding.tvPatternTitle.text = it
        }

        viewModel.patternRecommendation.observe(viewLifecycleOwner) {
            binding.tvPatternRecommendation.text = it
        }

        // Empty state
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.ersChart.visibility =
                if (empty) View.GONE else View.VISIBLE
            binding.tvReportsEmpty.visibility =
                if (empty) View.VISIBLE else View.GONE
            if (empty) {
                binding.ersChart.clear()
                binding.ersChart.invalidate()
            }
        }

        // Chart with real timestamps
        viewModel.chartPoints.observe(viewLifecycleOwner) { points ->
            if (points.isEmpty()) return@observe

            val period = viewModel.selectedPeriod.value ?: 0
            val baseTimestamp = points.first().first
            val entries = points.map { (timestamp, value) ->
                Entry((timestamp - baseTimestamp) / 1000f, value)
            }

            val dataSet = LineDataSet(entries, "Environmental Condition").apply {
                color = Color.parseColor("#1A6B8A")
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                fillAlpha = 50
                fillColor = Color.parseColor("#1A6B8A")
                setDrawFilled(true)
            }

            binding.ersChart.xAxis.valueFormatter =
                object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val timestamp = baseTimestamp + (value * 1000f).toLong()
                        return viewModel.getXAxisLabel(timestamp, period)
                    }
                }

            binding.ersChart.data = LineData(dataSet)
            binding.ersChart.invalidate()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
