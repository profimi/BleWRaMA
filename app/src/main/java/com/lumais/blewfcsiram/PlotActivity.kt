package com.lumais.blewrama

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumais.blewrama.data.CsibParser
import com.lumais.blewrama.data.MeasurementRepository
import com.lumais.blewrama.data.ParseResult
import com.lumais.blewrama.data.TimeSlot
import com.lumais.blewrama.databinding.ActivityPlotBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.io.File

class PlotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlotBinding
    private lateinit var repo: MeasurementRepository
    private var currentSlots: List<TimeSlot> = emptyList()

    // ── File picker ──────────────────────────────────────────────────────────
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            // Copy URI to a temp file so we can use random-access parsing
            val tmp = File(cacheDir, "plot_tmp.bin")
            contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out) }
            }
            loadAndPlot(tmp)
        } catch (e: Exception) {
            showError("Failed to open file: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Measurement Plot"
            setDisplayHomeAsUpEnabled(true)
        }

        repo = MeasurementRepository(this)
        setupChart()
        setupControls()

        // If launched with a file path extra, load it immediately
        intent.getStringExtra(EXTRA_FILE_PATH)?.let { loadAndPlot(File(it)) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnPickFile.setOnClickListener {
            filePicker.launch("*/*")
        }

        // Populate internal-files spinner
        refreshFileSpinner()

        binding.btnLoadInternal.setOnClickListener {
            val pos = binding.spinnerFiles.selectedItemPosition
            val files = repo.listCompactDatFiles()   // only CSIB .dat files are plottable
            if (pos in files.indices) loadAndPlot(files[pos])
            else showError("No file selected")
        }

        // Checkboxes redraw without re-parsing
        listOf(
            binding.cbRaw, binding.cbFiltered,
            binding.cbVariance, binding.cbValidRatio
        ).forEach { cb -> cb.setOnCheckedChangeListener { _, _ -> redrawChart() } }
    }

    private fun refreshFileSpinner() {
        val files = repo.listCompactDatFiles()   // only CSIB .dat files are plottable; .dat excluded
        val names = if (files.isEmpty()) listOf("— no `1_*.dat files —") else files.map { it.name }
        binding.spinnerFiles.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        binding.btnLoadInternal.isEnabled = files.isNotEmpty()
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun loadAndPlot(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        Thread {
            val result = CsibParser.parse(file)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                when (result) {
                    is ParseResult.Success -> {
                        val slots = CsibParser.aggregate(result.records)
                        currentSlots = slots
                        updateHeaderInfo(result.header.recordCount.toInt(), slots.size, file.name)
                        redrawChart()
                    }
                    is ParseResult.Error -> showError(result.message)
                }
            }
        }.start()
    }

    // ── Chart setup ───────────────────────────────────────────────────────────

    private fun setupChart() {
        with(binding.chart) {
            description.isEnabled = false
            setBackgroundColor(Color.parseColor("#1C1F2A"))
            setGridBackgroundColor(Color.parseColor("#111318"))

            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = true

            // X axis
            xAxis.apply {
                textColor = Color.parseColor("#7A8099")
                gridColor = Color.parseColor("#2A2D3A")
                axisLineColor = Color.parseColor("#4A9EFF")
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}"
                }
                setLabelCount(8, false)
            }

            // Left Y axis — distances & variance
            axisLeft.apply {
                textColor = Color.parseColor("#7A8099")
                gridColor = Color.parseColor("#2A2D3A")
                axisLineColor = Color.parseColor("#4A9EFF")
                setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
            }

            // Right Y axis — valid ratio [0..1]
            axisRight.apply {
                textColor = Color.parseColor("#F1C40F")
                gridColor = Color.TRANSPARENT
                axisLineColor = Color.parseColor("#F1C40F")
                axisMinimum = 0f
                axisMaximum = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "%.0f%%".format(value * 100)
                }
            }

            // Legend
            legend.apply {
                isEnabled = true
                textColor = Color.parseColor("#EAEDF3")
                textSize = 11f
                form = Legend.LegendForm.LINE
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
            }

            // Marker
            val marker = ChartMarkerView(context, R.layout.marker_view)
            marker.chartView = this
            this.marker = marker

            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) { /* marker handles it */ }
                override fun onNothingSelected() {}
            })
        }
    }

    // ── Redraw ────────────────────────────────────────────────────────────────

    private fun redrawChart() {
        val slots = currentSlots
        if (slots.isEmpty()) {
            binding.chart.clear()
            binding.chart.invalidate()
            return
        }

        val sets = mutableListOf<ILineDataSet>()

        // ── Raw distance ──────────────────────────────────────────────────
        if (binding.cbRaw.isChecked) {
            val entries = slots.filter { !it.distRaw.isNaN() }
                .map { Entry(it.timeHundredMs, it.distRaw) }
            sets += lineDataSet(
                entries, "Raw dist (m)",
                Color.parseColor("#4A9EFF"), YAxis.AxisDependency.LEFT,
                dashed = false, filled = false
            )
        }

        // ── Filtered distance ─────────────────────────────────────────────
        if (binding.cbFiltered.isChecked) {
            val entries = slots.filter { !it.distFiltered.isNaN() }
                .map { Entry(it.timeHundredMs, it.distFiltered) }
            sets += lineDataSet(
                entries, "Filtered dist (m)",
                Color.parseColor("#2ECC71"), YAxis.AxisDependency.LEFT,
                dashed = false, filled = true
            )
        }

        // ── Variance ──────────────────────────────────────────────────────
        if (binding.cbVariance.isChecked) {
            val entries = slots.filter { !it.variance.isNaN() }
                .map { Entry(it.timeHundredMs, it.variance) }
            sets += lineDataSet(
                entries, "Variance",
                Color.parseColor("#E74C3C"), YAxis.AxisDependency.LEFT,
                dashed = true, filled = false
            )
        }

        // ── Valid ratio ───────────────────────────────────────────────────
        if (binding.cbValidRatio.isChecked) {
            val entries = slots.map { Entry(it.timeHundredMs, it.validRatio) }
            sets += lineDataSet(
                entries, "Valid ratio",
                Color.parseColor("#F1C40F"), YAxis.AxisDependency.RIGHT,
                dashed = false, filled = false
            ).also { it.lineWidth = 1.5f }
        }

        binding.chart.data = if (sets.isEmpty()) null else LineData(sets)
        binding.chart.notifyDataSetChanged()
        binding.chart.invalidate()
    }

    private fun lineDataSet(
        entries: List<Entry>,
        label: String,
        color: Int,
        axis: YAxis.AxisDependency,
        dashed: Boolean,
        filled: Boolean
    ): LineDataSet = LineDataSet(entries, label).apply {
        this.color = color
        setDrawCircles(entries.size < 80)   // dots only when few points
        setDrawCircleHole(false)
        circleRadius = 2.5f
        setCircleColor(color)
        lineWidth = 2f
        setDrawValues(false)
        axisDependency = axis
        mode = LineDataSet.Mode.LINEAR
        if (dashed) enableDashedLine(10f, 5f, 0f)
        if (filled) {
            setDrawFilled(true)
            fillAlpha = 30
            fillColor = color
        }
        highLightColor = Color.WHITE
        highlightLineWidth = 1f
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateHeaderInfo(records: Int, slots: Int, name: String) {
        binding.tvFileInfo.text = "📄 $name  |  $records records  |  $slots × 100 ms slots"
        binding.tvFileInfo.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        binding.tvError.text = "⚠ $msg"
        binding.tvError.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
