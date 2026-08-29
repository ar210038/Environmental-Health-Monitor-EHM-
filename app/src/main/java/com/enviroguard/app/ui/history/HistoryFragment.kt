package com.enviroguard.app.ui.history

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.datepicker.MaterialDatePicker
import com.enviroguard.app.utils.DateRangeUtils

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HistoryViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = ViewModelFactory(
            (requireActivity().application as EnviroGuardApp).repository
        )
        viewModel = ViewModelProvider(this, factory)[HistoryViewModel::class.java]

        setupChart()
        setupChips()
        setupRangeTabs()
        observeViewModel()
    }

    private fun setupChart() {
        binding.historyChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            legend.isEnabled = false
        }
    }

    private fun setupChips() {
        val chips = listOf(
            binding.chipTemperature,
            binding.chipHumidity,
            binding.chipTvoc,
            binding.chipEco2,
            binding.chipNoise,
            binding.chipHeatIndex
        )

        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                chips.forEach { c ->
                    c.setBackgroundResource(R.drawable.bg_chip_unselected)
                    c.setTextColor(Color.parseColor("#5B6664"))
                    c.setTypeface(null, Typeface.NORMAL)
                }
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(Color.WHITE)
                chip.setTypeface(null, Typeface.BOLD)
                viewModel.selectSensor(index)
            }
        }
    }

    private fun setupRangeTabs() {
        val tabs = listOf(
            binding.rangeToday,
            binding.range7Days,
            binding.range30Days,
            binding.rangeCustom
        )

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                if (index == 3) {
                    showCustomDatePicker(tabs)
                    return@setOnClickListener
                }
                tabs.forEach { t ->
                    t.setTextColor(Color.parseColor("#9AA5A3"))
                    t.setBackgroundResource(0)
                    t.setTypeface(null, Typeface.NORMAL)
                }
                tab.setTextColor(Color.parseColor("#1A6B8A"))
                tab.setBackgroundResource(R.drawable.bg_tab_active)
                tab.setTypeface(null, Typeface.BOLD)
                viewModel.selectRange(index)
            }
        }
    }

    private fun showCustomDatePicker(tabs: List<android.widget.TextView>) {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select history date range")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = DateRangeUtils.materialPickerDate(selection.first)
            val end = DateRangeUtils.materialPickerDate(selection.second)
            tabs.forEach { tab ->
                tab.setTextColor(Color.parseColor("#9AA5A3"))
                tab.setBackgroundResource(0)
                tab.setTypeface(null, Typeface.NORMAL)
            }
            binding.rangeCustom.setTextColor(Color.parseColor("#1A6B8A"))
            binding.rangeCustom.setBackgroundResource(R.drawable.bg_tab_active)
            binding.rangeCustom.setTypeface(null, Typeface.BOLD)
            viewModel.selectCustomRange(start, end)
        }
        picker.show(parentFragmentManager, "history_date_range")
    }

    private fun observeViewModel() {

        viewModel.chartTitle.observe(viewLifecycleOwner) {
            binding.tvHistoryChartTitle.text = it
        }

        viewModel.minValue.observe(viewLifecycleOwner) {
            binding.tvMinValue.text = it
        }

        viewModel.avgValue.observe(viewLifecycleOwner) {
            binding.tvAvgValue.text = it
        }

        viewModel.maxValue.observe(viewLifecycleOwner) {
            binding.tvMaxValue.text = it
        }

        // Empty state
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.historyChart.visibility =
                if (empty) View.GONE else View.VISIBLE
            binding.tvHistoryEmpty.visibility =
                if (empty) View.VISIBLE else View.GONE
            if (empty) {
                binding.historyChart.clear()
                binding.historyChart.invalidate()
            }
        }

        // Chart data with real timestamps
        viewModel.chartPoints.observe(viewLifecycleOwner) { points ->
            if (points.isEmpty()) return@observe

            val range = viewModel.selectedRange.value ?: 0
            val color = Color.parseColor(viewModel.getChartColor())

            val baseTimestamp = points.first().first
            val entries = points.map { (timestamp, value) ->
                Entry((timestamp - baseTimestamp) / 1000f, value)
            }

            val dataSet = LineDataSet(entries, "").apply {
                this.color = color
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                fillAlpha = 50
                fillColor = color
                setDrawFilled(true)
            }

            // X axis labels from timestamps
            binding.historyChart.xAxis.valueFormatter =
                object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val timestamp = baseTimestamp + (value * 1000f).toLong()
                        return viewModel.getXAxisLabel(timestamp, range)
                    }
                }

            binding.historyChart.data = LineData(dataSet)
            binding.historyChart.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) viewModel.refreshTemperatureUnit()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
