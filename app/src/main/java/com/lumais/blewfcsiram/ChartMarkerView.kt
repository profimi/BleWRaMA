package com.lumais.blewrama

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

class ChartMarkerView(context: Context, layoutResource: Int) :
    MarkerView(context, layoutResource) {

    private val tvContent: TextView = findViewById(R.id.tvMarkerContent)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        e ?: return
        val timeMs = (e.x * 100).toInt()
        tvContent.text = "t = ${timeMs} ms\ny = ${"%.4f".format(e.y)}"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF =
        MPPointF(-(width / 2f), -height.toFloat() - 8f)
}
