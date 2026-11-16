package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.jarvis.core.model.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Анимированное «ядро» JARVIS из jarvis_core_home.html / jarvis_core_overlay.html.
 * В compact-режиме рисует плавающий пузырь оверлея (84dp зона, орб 54px).
 */
class CoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var isCompact: Boolean = false
        set(value) { field = value; requestLayout() }

    var state: AssistantState = AssistantState.READY
        set(value) {
            if (field == value) return
            field = value
            applyStateColors()
            invalidate()
        }

    private var coreColor: Int = CoreTheme.BRASS
    private var deepColor: Int = CoreTheme.BRASS_DEEP
    private var breatheMs: Long = 3600L

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    init {
        applyStateColors()
    }

    private fun applyStateColors() {
        val c = CoreTheme.forState(state)
        coreColor = c.core
        deepColor = c.deep
        breatheMs = c.breatheMs
    }

    /** Цвета оверлея подписываются из JarvisEvents, не трогаем внутренний state. */
    fun setStateColors(core: Int, deep: Int, periodMs: Long) {
        coreColor = core
        deepColor = deep
        breatheMs = periodMs
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator.isStarted.not()) animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (if (isCompact) dp(88f) else dp(266f)).toInt()
        val w = resolveSizeDefault(defaultSize, widthMeasureSpec)
        val h = resolveSizeDefault(defaultSize, heightMeasureSpec)
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    private fun resolveSizeDefault(defaultSize: Int, measureSpec: Int): Int {
        val mode = MeasureSpec.getMode(measureSpec)
        val size = MeasureSpec.getSize(measureSpec)
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> minOf(defaultSize, size)
            else -> defaultSize
        }
    }

    private fun single(time: Long, periodMs: Long): Float =
        ((time % periodMs).toFloat() / periodMs.toFloat())

    private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val t = SystemClock.elapsedRealtime()
        val cx = width / 2f
        val cy = height / 2f
        val s = if (isCompact) width / 88f else width / 266f
        val core = coreColor
        val deep = deepColor

        // halo
        val haloR = (if (isCompact) 44f else 105f) * s * (1f + 0.13f * (0.5f - 0.5f * cos(2f * PI.toFloat() * single(t, 4200L))))
        val haloColors = intArrayOf(CoreTheme.withAlpha(core, 48), 0)
        paint.shader = RadialGradient(cx, cy, haloR, haloColors, floatArrayOf(0f, 0.66f), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, haloR, paint)
        paint.shader = null

        // pulse rings
        val pulseBase = if (isCompact) 31f else 75f
        val pulsePeriod = if (isCompact) 3000L else 3400L
        repeat(3) { i ->
            val p = single(t + i * if (isCompact) 1000L else 1130L, pulsePeriod)
            val r = (0.88f + 0.97f * ease(p)) * pulseBase * s
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * s
            paint.color = CoreTheme.withAlpha(core, (85 * (1f - p)).toInt())
            canvas.drawCircle(cx, cy, r, paint)
        }

        // static faint rings
        paint.style = Paint.Style.STROKE
        paint.color = CoreTheme.BORDER
        paint.shader = null
        val outerR = (if (isCompact) 39f else 64f) * s
        val innerR = (if (isCompact) 33f else 52f) * s
        paint.strokeWidth = 1f * s
        canvas.drawCircle(cx, cy, outerR, paint)
        paint.pathEffect = DashPathEffect(floatArrayOf(2f * s, 8f * s), 0f)
        paint.alpha = 140
        canvas.drawCircle(cx, cy, innerR, paint)
        paint.alpha = 255
        paint.pathEffect = null

        // tick marks (only on the big core, compact ring is too small)
        if (!isCompact) {
            val ticksAngle = single(t, 60000L) * 360f
            val rectOuter = dp(68f * s.coerceAtLeast(1f)) // unused visual guard
            for (i in 0 until 72) {
                val a = (i * 5) * PI.toFloat() / 180f + ticksAngle * PI.toFloat() / 180f
                val long = i % 6 == 0
                val r1 = (if (long) 68f else 70f) * s
                val r2 = 73f * s
                val x1 = cx + cos(a) * r1
                val y1 = cy + sin(a) * r1
                val x2 = cx + cos(a) * r2
                val y2 = cy + sin(a) * r2
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (if (long) 1.4f else 1f) * s
                paint.color = CoreTheme.withAlpha(if (long) core else CoreTheme.BORDER, if (long) 200 else 140)
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
        }

        // rotating arcs (comets)
        drawArc(canvas, cx, cy, outerR, single(t, 26000L) * 360f, core, (if (isCompact) 2.2f else 2.5f) * s, if (isCompact) 67f else 67f)
        if (!isCompact) {
            drawArc(canvas, cx, cy, outerR, single(t, 26000L) * 360f, core, 1.5f * s, 27f, offsetDeg = -170f, alpha = 150)
        }
        drawArc(canvas, cx, cy, innerR, -single(t, 14000L) * 360f, CoreTheme.TEAL, (if (isCompact) 1.6f else 2f) * s, if (isCompact) 73f else 73f)

        // orbiting particles
        if (isCompact) {
            drawOrbitParticle(canvas, cx, cy, 30f * s, single(t, 9000L) * 360f, core, 3f * s, 255)
            drawOrbitParticle(canvas, cx, cy, 22f * s, -single(t, 15000L) * 360f, CoreTheme.TEAL, 2f * s, 230)
        } else {
            drawOrbitParticle(canvas, cx, cy, 62f * s, single(t, 9000L) * 360f, core, 3f * s, 255)
            drawOrbitParticle(canvas, cx, cy, 46f * s, -single(t, 15000L) * 360f, CoreTheme.TEAL, 2f * s, 230)
            drawOrbitParticle(canvas, cx, cy, 84f * s, single(t, 21000L) * 360f, CoreTheme.withAlpha(core, 230), 2.5f * s, 200)
        }

        // orb
        drawOrb(canvas, cx, cy, (if (isCompact) 27f else 66f) * s, core, deep, t)
    }

    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, r: Float, rotationDeg: Float, color: Int, width: Float, sweepDeg: Float, offsetDeg: Float = 0f, alpha: Int = 255) {
        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                CoreTheme.withAlpha(color, 0),
                CoreTheme.withAlpha(color, alpha),
                CoreTheme.withAlpha(color, 0),
            ),
            floatArrayOf(0f, 0.28f, 1f),
        )
        canvas.drawArc(rect, offsetDeg, sweepDeg, false, paint)
        paint.shader = null
        paint.strokeCap = Paint.Cap.BUTT
        canvas.restore()
    }

    private fun drawOrbitParticle(canvas: Canvas, cx: Float, cy: Float, radius: Float, angleDeg: Float, color: Int, size: Float, alpha: Int) {
        val a = angleDeg * PI.toFloat() / 180f
        val x = cx + cos(a) * radius
        val y = cy + sin(a) * radius
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = CoreTheme.withAlpha(color, alpha / 2)
        canvas.drawCircle(x, y, size * 1.9f, paint)
        paint.color = CoreTheme.withAlpha(color, alpha)
        canvas.drawCircle(x, y, size, paint)
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, r: Float, core: Int, deep: Int, t: Long) {
        val breathe = 1f + 0.045f * sin(2f * PI.toFloat() * single(t, breatheMs.coerceAtLeast(1)))
        val orbR = r * breathe

        // outer glow
        paint.shader = RadialGradient(
            cx, cy, orbR * 1.5f,
            intArrayOf(CoreTheme.withAlpha(core, 90), CoreTheme.withAlpha(core, 24), 0),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, orbR * 1.5f, paint)

        // orb body
        val lx = cx - orbR * 0.16f
        val ly = cy - orbR * 0.22f
        val body = RadialGradient(
            lx, ly, orbR * 1.15f,
            intArrayOf(mixColor(core, CoreTheme.TEXT, 0.08f), core, deep, deepEnd(deep)),
            floatArrayOf(0f, 0.32f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = body
        canvas.drawCircle(cx, cy, orbR, paint)

        // inner shading
        val shade = RadialGradient(
            cx, cy, orbR,
            intArrayOf(0x00FFFFFF.toInt(), CoreTheme.withAlpha(CoreTheme.BG_DEEP, 45), CoreTheme.withAlpha(CoreTheme.BG_DEEP, 140)),
            floatArrayOf(0.55f, 0.85f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = shade
        canvas.drawCircle(cx, cy, orbR, paint)
        paint.shader = null

        // specular top-left
        paint.color = CoreTheme.withAlpha(CoreTheme.TEXT, 46)
        canvas.drawCircle(cx - orbR * 0.34f, cy - orbR * 0.4f, orbR * 0.22f, paint)
        paint.color = CoreTheme.withAlpha(CoreTheme.TEXT, 20)
        canvas.drawCircle(cx - orbR * 0.24f, cy - orbR * 0.28f, orbR * 0.42f, paint)

        // sweeping glint
        canvas.save()
        canvas.rotate(single(t, 4400L) * 360f, cx, cy)
        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(0, CoreTheme.withAlpha(CoreTheme.TEXT, 64), 0),
            floatArrayOf(0f, 0.1f, 0.3f),
        )
        canvas.drawCircle(cx, cy, orbR, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun mixColor(base: Int, blend: Int, blendFraction: Float): Int {
        val r = (android.graphics.Color.red(base) * (1f - blendFraction) + android.graphics.Color.red(blend) * blendFraction).toInt()
        val g = (android.graphics.Color.green(base) * (1f - blendFraction) + android.graphics.Color.green(blend) * blendFraction).toInt()
        val b = (android.graphics.Color.blue(base) * (1f - blendFraction) + android.graphics.Color.blue(blend) * blendFraction).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    private fun deepEnd(deep: Int): Int = deep
}