package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class StateGlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var mode: CoreTheme.GlyphMode = CoreTheme.GlyphMode.OFF
    var coreColor: Int = CoreTheme.BRASS

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!animator.isStarted) animator.start() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val barH = dp(28f)
        setMeasuredDimension(dp(62f).toInt(), barH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (mode == CoreTheme.GlyphMode.OFF) return
        val t = SystemClock.elapsedRealtime()

        if (mode == CoreTheme.GlyphMode.THINKING) {
            val dotR = dp(3.5f)
            val cx = w / 2f
            repeat(3) { i ->
                val a = (t % 1300L + i * 180L) % 1300L / 1300f
                val bounce = sin(a * 2f * PI.toFloat()) * dp(6f)
                val y = h / 2f - dotR + bounce
                paint.color = CoreTheme.VIOLET
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx - dp(12f) + i * dp(12f), y, dotR, paint)
            }
            return
        }

        // wave bars
        val isSoft = mode == CoreTheme.GlyphMode.WAVE_SOFT
        val bars = 12
        val gap = dp(3f)
        val barW = (w - gap * (bars - 1)) / bars
        val maxBarH = dp(if (isSoft) 16f else 28f)
        val minBarH = dp(4f)

        paint.style = Paint.Style.FILL
        paint.color = coreColor
        val path = Path()

        for (i in 0 until bars) {
            val phase = (t % 1000L).toFloat() / 1000f + i * 0.09f
            val amp = if (isSoft) 0.4f + 0.3f * sin(phase * 2f * PI.toFloat()).toFloat()
                      else 0.5f + 0.5f * sin(phase * 2f * PI.toFloat() + i * 0.3f).toFloat()
            val barH = minBarH + (maxBarH - minBarH) * amp
            val x = i * (barW + gap)
            val top = h - barH
            path.addRoundRect(
                RectF(x, top, x + barW, h),
                barW / 2f,
                barW / 2f,
                Path.Direction.CW,
            )
        }
        canvas.drawPath(path, paint)
    }

    fun setMode(newMode: CoreTheme.GlyphMode, color: Int) {
        if (mode == newMode && coreColor == color) return
        mode = newMode
        coreColor = color
        invalidate()
    }
}