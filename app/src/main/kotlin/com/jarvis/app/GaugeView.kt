package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    var label: String = ""
    var caption: String = ""
    var accent: Int = Color.parseColor("#62E9F4")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height * 0.46f
        val r = min(width, height) * 0.34f
        arc.set(cx - r, cy - r, cx + r, cy + r)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.parseColor("#183848")
        canvas.drawArc(arc, 140f, 260f, false, paint)
        paint.color = accent
        canvas.drawArc(arc, 140f, 260f * (value / 100f), false, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#F7F8FB")
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.42f
        canvas.drawText("${value.toInt()}", cx, cy + r * 0.12f, paint)
        paint.color = accent
        paint.textSize = r * 0.22f
        canvas.drawText(label, cx, cy + r * 0.95f, paint)
        paint.color = Color.parseColor("#8F9BAE")
        paint.textSize = r * 0.18f
        canvas.drawText(caption, cx, cy + r * 1.25f, paint)
    }
}
