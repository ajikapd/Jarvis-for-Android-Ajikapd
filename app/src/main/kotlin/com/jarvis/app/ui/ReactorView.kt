package com.jarvis.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Живое ядро: механический реактор. Кольца вращаются, панели отвечают на голос, шаги идут по внешнему кольцу. */
class ReactorView(context: Context, private val skin: Skin) : View(context) {
    enum class Mode(val energy: Float, val speed: Float) {
        OFF(0f, 0f), IDLE(.2f, .3f), LISTEN(.6f, .7f), THINK(.45f, 1.7f), ACT(.55f, 1.1f), SPEAK(.85f, .55f),
    }

    var mode: Mode = Mode.OFF
    /** Текущий шаг ответа, 0..4; показывается дугами на внешнем кольце. */
    var step: Int = -1
    /** Рывок раскрутки при включении. */
    fun kick() { boost = 1f }

    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private var energy = 0f
    private var spin = 0f
    private var power = 0f
    private var boost = 0f
    private var phase = 0f
    private var last = 0L

    private fun arc(c: Canvas, cx: Float, cy: Float, r: Float, startRad: Float, sweepRad: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, Math.toDegrees(startRad.toDouble()).toFloat(), Math.toDegrees(sweepRad.toDouble()).toFloat(), false, stroke)
    }

    private fun plates(c: Canvas, cx: Float, cy: Float, r: Float, n: Int, w: Float, span: Float, rot: Float) {
        for (i in 0 until n) {
            val a = i * TAU / n + rot
            arc(c, cx, cy, r, a, span, skin.metal, w)
            arc(c, cx, cy, r + w / 2, a, span, alpha(Color.parseColor("#BED4EB"), .42f), 1f)
            arc(c, cx, cy, r - w / 2, a, span, alpha(Color.BLACK, .5f), 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(.05f)
        last = now
        val t = now.toFloat()
        energy += (mode.energy - energy) * .06f
        spin += (mode.speed - spin) * .05f
        power += ((if (mode == Mode.OFF) 0f else 1f) - power) * .06f
        boost *= .965f
        phase += dt * (spin + boost * 2.6f)
        val e = energy + boost * .5f
        val beat = when (mode) {
            Mode.OFF -> 0f
            Mode.SPEAK -> abs(sin(t / 130f) * sin(t / 410f))
            Mode.LISTEN -> abs(sin(t / 240f)) * .6f
            else -> sin(t / 1500f) * .2f + .2f
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) * .47f
        val dim = .3f + .7f * power
        val layer = canvas.saveLayer(0f, 0f, w, h, null)

        // Свечение
        fill.xfermode = add
        fill.shader = RadialGradient(
            cx, cy, r * 1.25f,
            intArrayOf(alpha(skin.accent, .3f + e * .35f), alpha(skin.accent, .1f + e * .1f), alpha(skin.accent, 0f)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 1.25f, fill)
        fill.shader = null
        fill.xfermode = null

        // Риски внешнего кольца
        stroke.color = alpha(skin.accent, .55f)
        stroke.strokeWidth = 1f
        for (i in 0 until 90) {
            val a = i * TAU / 90 + phase * .12f
            val l = if (i % 5 == 0) .94f else .97f
            canvas.drawLine(cx + cos(a) * r * l, cy + sin(a) * r * l, cx + cos(a) * r, cy + sin(a) * r, stroke)
        }

        // Металл
        plates(canvas, cx, cy, r * .88f, 12, r * .085f, .34f, -phase * .22f)
        plates(canvas, cx, cy, r * .6f, 10, r * .06f, .42f, phase * .34f)

        stroke.xfermode = add
        // Оранжевые вставки
        val hotA = .35f + e * .55f
        for (i in 0 until 6) {
            val a = i * TAU / 6 - phase * .22f + .42f
            arc(canvas, cx, cy, r * .77f, a, .46f, alpha(skin.hot, hotA * .28f), r * .035f)
            arc(canvas, cx, cy, r * .77f, a, .46f, alpha(skin.hot, hotA.coerceAtMost(1f)), 2f)
        }
        // Панели отвечают на голос
        val n = 28
        val sweepFrac = (t / 1800f) % 1f
        for (i in 0 until n) {
            val a = i * TAU / n + phase * .42f
            var v = .26f + e * .3f
            if (mode == Mode.SPEAK) v += .55f * abs(sin(t / 160f + i * .9f)) * beat
            if (mode == Mode.LISTEN) v += .45f * abs(sin(t / 300f + i * .4f))
            if (mode == Mode.ACT) v = if (i.toFloat() / n < sweepFrac) .95f else .2f
            arc(canvas, cx, cy, r * .69f, a, .17f, alpha(skin.accent, v.coerceAtMost(1f)), r * .052f)
        }
        for (i in 0 until 16) {
            val a = i * TAU / 16 - phase * .7f
            arc(canvas, cx, cy, r * .46f, a, .3f, alpha(skin.cyan, (.35f + e * .5f).coerceAtMost(1f) * .7f), r * .09f)
        }
        arc(canvas, cx, cy, r * .36f, 0f, TAU, alpha(skin.cyan, .6f), 1f)

        // Пять шагов ответа прямо на кольце
        if (mode == Mode.THINK) {
            val seg = TAU / 5
            val gap = .12f
            for (i in 0 until 5) {
                val a = -HALF_PI + i * seg + gap / 2
                val sw = seg - gap
                arc(canvas, cx, cy, r * .965f, a, sw, alpha(skin.accent, .22f), 3f)
                if (i < step) arc(canvas, cx, cy, r * .965f, a, sw, skin.cyan, 4f)
                if (i == step) {
                    val f = .25f + .75f * abs(sin(t / 260f))
                    arc(canvas, cx, cy, r * .965f, a, sw, alpha(skin.cyan, f), 4f)
                    arc(canvas, cx, cy, r * .8f, a + sw * ((t / 700f) % 1f), .5f, alpha(skin.cyan, .95f), 3f)
                }
            }
        }
        stroke.xfermode = null

        // Сердце
        val core = r * (.24f + e * .09f + beat * .05f) * (.35f + .65f * power)
        fill.xfermode = add
        fill.shader = RadialGradient(
            cx, cy, core * 1.5f,
            intArrayOf(Color.WHITE, alpha(skin.cyan, .95f), alpha(skin.accent, .45f), alpha(skin.accent, 0f)),
            floatArrayOf(0f, .28f, .62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, core * 1.5f, fill)
        fill.shader = null
        fill.xfermode = null
        stroke.color = alpha(Color.WHITE, .75f)
        stroke.strokeWidth = 1.5f
        stroke.xfermode = add
        for (i in 0 until 8) {
            val a = i * TAU / 8 + phase * .2f
            val l = core * (if (i % 2 == 1) 1.1f else 1.7f + beat * .5f)
            canvas.drawLine(cx, cy, cx + cos(a) * l, cy + sin(a) * l, stroke)
        }
        stroke.xfermode = null

        if (dim < .999f) {
            fill.xfermode = dstIn
            fill.color = alpha(Color.BLACK, dim)
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.xfermode = null
        }
        canvas.restoreToCount(layer)
        if (isShown) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        /** 30 кадров хватает глазу, а на полной частоте экрана реактор съедает целое ядро. */
        const val FRAME_MS = 33L
        const val TAU = 6.2831855f
        const val HALF_PI = 1.5707964f
    }
}
