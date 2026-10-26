package com.jarvis.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.jarvis.core.model.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Arc reactor ported from jarvis_mobile_ui startCore():
 * radial bloom, indexed dial, opposing segments, polyhedral aperture.
 */
class ReactorCoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    var state: AssistantState = AssistantState.READY
    var accent: Int = Color.parseColor("#62E9F4")
    var accent2: Int = Color.parseColor("#2FB9CA")
    var hot: Int = Color.parseColor("#F3BB58")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    private val glowSoft = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
    private var running = false
    private val t0 = SystemClock.uptimeMillis()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

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
        val tm = (SystemClock.uptimeMillis() - t0).toFloat()
        val t = tm * 0.00024f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.40f
        val speed = when (state) {
            AssistantState.THINKING, AssistantState.EXECUTING -> 2.2f
            AssistantState.SPEAKING -> 1.5f
            AssistantState.LISTENING -> 1.15f
            else -> 1f
        }
        canvas.save()
        canvas.translate(cx, cy)

        val fog = RadialGradient(
            0f, 0f, r * 1.16f,
            intArrayOf(alpha(accent, 0x55), alpha(accent2, 0x28), Color.TRANSPARENT),
            floatArrayOf(0f, 0.44f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.style = Paint.Style.FILL
        paint.shader = fog
        paint.maskFilter = glowSoft
        canvas.drawCircle(0f, 0f, r * 1.2f, paint)
        paint.shader = null
        paint.maskFilter = null

        paint.style = Paint.Style.STROKE
        for (ring in 0 until 6) {
            val rr = r * (0.49f + ring * 0.092f)
            paint.color = if (ring % 2 == 0) alpha(accent, 0x80) else alpha(accent2, 0x55)
            paint.strokeWidth = if (ring == 5) 1.6f else 1.1f
            paint.pathEffect = when (ring) {
                2 -> DashPathEffect(floatArrayOf(4f, 8f), 0f)
                4 -> DashPathEffect(floatArrayOf(14f, 6f, 2f, 6f), 0f)
                else -> null
            }
            canvas.drawCircle(0f, 0f, rr, paint)
        }
        paint.pathEffect = null

        canvas.save()
        canvas.rotate(-t * 40f * speed)
        for (i in 0 until 180) {
            val ang = i * PI.toFloat() * 2f / 180f
            val major = i % 15 == 0
            val mid = i % 5 == 0
            val inner = r * 0.925f
            val outer = inner - if (major) 14f else if (mid) 8f else 4f
            paint.color = when {
                major -> accent
                mid -> alpha(accent, 0xA0)
                else -> alpha(accent, 0x45)
            }
            paint.strokeWidth = if (major) 1.6f else 0.8f
            canvas.drawLine(cos(ang) * inner, sin(ang) * inner, cos(ang) * outer, sin(ang) * outer, paint)
        }
        canvas.restore()

        val layers = intArrayOf(28, 18, 12, 8)
        val widths = floatArrayOf(2.4f, 3.2f, 2.6f, 2.2f)
        for (layer in 0 until 4) {
            canvas.save()
            val dir = if (layer % 2 == 0) -1f else 1f
            canvas.rotate(t * dir * (40f + layer * 18f) * speed)
            val rr = r * (0.69f - layer * 0.092f)
            val n = layers[layer]
            for (i in 0 until n) {
                val start = Math.toDegrees((i * 2.0 * PI / n) + 0.035).toFloat()
                val sweep = Math.toDegrees((2.0 * PI / n) - 0.105).toFloat()
                paint.color = when {
                    i % 4 == 0 -> accent
                    i % 2 == 0 -> alpha(accent2, 0xCF)
                    else -> alpha(accent, 0x4A)
                }
                paint.strokeWidth = widths[layer]
                paint.maskFilter = if (i % 4 == 0) glow else null
                canvas.drawArc(-rr, -rr, rr, rr, start, sweep, false, paint)
            }
            paint.maskFilter = null
            canvas.restore()
        }

        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = 1.2f
        for (i in 0 until 12) {
            canvas.save()
            canvas.rotate(Math.toDegrees(i * PI / 6.0 + t * 0.9).toFloat())
            paint.color = alpha(accent, 0x99)
            canvas.drawLine(r * 0.30f, 0f, r * 0.61f, 0f, paint)
            canvas.restore()
        }

        canvas.save()
        canvas.rotate(-t * 50f * speed - 90f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = accent
        paint.maskFilter = glow
        val path = android.graphics.Path()
        val pr = r * 0.34f
        for (i in 0..3) {
            val an = (i * PI.toFloat() * 2f / 3f)
            val rr = pr * if (i == 1) 0.96f else 1f
            val x = cos(an) * rr
            val y = sin(an) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        canvas.restore()

        canvas.save()
        canvas.rotate(t * 70f * speed)
        paint.color = accent2
        paint.strokeWidth = 2f
        paint.maskFilter = glow
        canvas.drawCircle(0f, 0f, r * 0.245f, paint)
        paint.maskFilter = null
        paint.pathEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f)
        paint.color = alpha(accent, 0xAA)
        canvas.drawCircle(0f, 0f, r * 0.20f, paint)
        paint.pathEffect = null
        canvas.restore()

        val pulse = 1f + 0.04f * sin(tm * 0.003f)
        val coreR = r * 0.22f * pulse
        paint.style = Paint.Style.FILL
        paint.maskFilter = glowSoft
        paint.shader = RadialGradient(
            0f, 0f, coreR,
            intArrayOf(Color.parseColor("#F4FEFF"), accent, alpha(accent2, 0xEE), Color.TRANSPARENT),
            floatArrayOf(0f, 0.12f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, coreR, paint)
        paint.shader = null
        paint.maskFilter = null

        paint.style = Paint.Style.STROKE
        paint.maskFilter = null
        paint.color = Color.parseColor("#F4FEFF")
        paint.strokeWidth = 1.5f
        val spin = t * 0.55f
        val hex = android.graphics.Path()
        val hexR = r * 0.145f
        for (i in 0 until 6) {
            val ang = i * PI.toFloat() / 3f + spin
            val x = cos(ang) * hexR
            val y = sin(ang) * hexR
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
            val tick = hexR * 1.22f
            canvas.drawLine(cos(ang) * hexR, sin(ang) * hexR, cos(ang) * tick, sin(ang) * tick, paint)
        }
        hex.close()
        canvas.drawPath(hex, paint)
        paint.strokeWidth = 1.2f
        val tri = android.graphics.Path()
        val triR = r * 0.078f
        for (i in 0 until 3) {
            val ang = i * PI.toFloat() * 2f / 3f - spin * 1.4f - PI.toFloat() / 2f
            val x = cos(ang) * triR
            val y = sin(ang) * triR
            if (i == 0) tri.moveTo(x, y) else tri.lineTo(x, y)
        }
        tri.close()
        canvas.drawPath(tri, paint)
        paint.color = Color.parseColor("#FFFFFF")
        canvas.drawCircle(0f, 0f, r * 0.012f, paint.apply { style = Paint.Style.FILL })
        paint.style = Paint.Style.STROKE

        paint.style = Paint.Style.FILL
        for (i in 0 until 78) {
            val angle = rand(i + 2) * PI.toFloat() * 2f + t * (0.7f + rand(i) * 0.5f) * speed
            val rad = r * (0.68f + rand(i + 8) * 0.46f)
            val sz = 1.1f + rand(i + 70) * 2.2f
            paint.color = when {
                i % 7 == 0 -> hot
                i % 5 == 0 -> accent
                else -> alpha(accent2, 0xBB)
            }
            paint.maskFilter = if (i % 9 == 0) glow else null
            canvas.drawCircle(cos(angle) * rad, sin(angle) * rad, sz, paint)
        }
        paint.maskFilter = null
        for (i in 0 until 4) {
            val an = t * (if (i % 2 == 0) 2.4f else -2.8f) * speed + i * PI.toFloat() / 2f
            val rad = r * (0.75f + (i % 2) * 0.05f)
            paint.color = if (i == 1) hot else accent
            paint.maskFilter = glow
            canvas.drawCircle(cos(an) * rad, sin(an) * rad, 3.4f, paint)
        }
        paint.maskFilter = null
        canvas.restore()
    }

    private fun rand(seed: Int): Float {
        val x = sin(seed * 127.1 + 311.7) * 43758.5453
        return (x - floor(x)).toFloat()
    }

    private fun alpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
