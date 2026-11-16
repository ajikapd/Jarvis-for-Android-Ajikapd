package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.sin

class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    var active: Boolean = false
    var accent: Int = Color.parseColor("#62E9F4")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running = false
    private val bars = 24

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val gap = width / (bars * 3f)
        val barW = (width - gap * (bars + 1)) / bars
        paint.color = accent
        for (i in 0 until bars) {
            val wave = if (active) {
                (sin(t / 90.0 + i * 0.55) + 1.0) / 2.0
            } else {
                0.08 + 0.10 * ((sin(t / 700.0 + i * 0.4) + 1.0) / 2.0)
            }
            val h = (height * (0.12f + 0.84f * wave)).toFloat()
            val x = gap + i * (barW + gap)
            paint.alpha = if (active) 230 else 120
            canvas.drawRect(x, height - h, x + barW, height.toFloat(), paint)
        }
    }
}
