package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class TelemetryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val cpu = ArrayDeque<Float>()
    private val ram = ArrayDeque<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fill = Path()
    private val density = resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun seed(cpuBase: Float, ramBase: Float) {
        cpu.clear()
        ram.clear()
        repeat(30) {
            cpu.addLast((cpuBase + Random.nextFloat() * 6f - 3f).coerceIn(0f, 100f))
            ram.addLast((ramBase + Random.nextFloat() * 6f - 3f).coerceIn(0f, 100f))
        }
        invalidate()
    }

    fun push(cpuLoad: Float, ramLoad: Float) {
        if (cpu.size >= 30) cpu.removeFirst()
        if (ram.size >= 30) ram.removeFirst()
        cpu.addLast(cpuLoad.coerceIn(0f, 100f))
        ram.addLast(ramLoad.coerceIn(0f, 100f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val grid = Color.argb(31, 0, 229, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = grid
        paint.shader = null
        paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        paint.pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
        for (i in 1..4) {
            val y = height * i / 5f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        for (i in 1..6) {
            val x = width * i / 7f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
        paint.pathEffect = null
        val legendTop = 8f * density
        drawSeries(canvas, cpu, Color.parseColor("#00E5FF"), Color.argb(56, 0, 229, 255), legendTop)
        drawSeries(canvas, ram, Color.parseColor("#FFC400"), Color.argb(46, 255, 196, 0), legendTop)
        paint.style = Paint.Style.FILL
        paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        paint.textSize = 9f * density
        paint.color = Color.parseColor("#00E5FF")
        canvas.drawText("● CPU %", width - 92f * density, 14f * density, paint)
        paint.color = Color.parseColor("#FFC400")
        canvas.drawText("● RAM %", width - 46f * density, 14f * density, paint)
    }

    private fun drawSeries(canvas: Canvas, data: ArrayDeque<Float>, color: Int, fillColor: Int, topPad: Float) {
        if (data.size < 2) return
        path.reset()
        val right = width - 8f
        val bottom = height - 6f
        val span = (data.size - 1).toFloat()
        data.forEachIndexed { i, v ->
            val x = i * (right / span)
            val y = bottom - (v / 100f) * (bottom - topPad - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        fill.set(path)
        fill.lineTo(right, bottom)
        fill.lineTo(0f, bottom)
        fill.close()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, topPad, 0f, bottom, fillColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fill, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.color = color
        paint.setShadowLayer(10f * density, 0f, 0f, color)
        canvas.drawPath(path, paint)
    }
}
