package com.jarvis.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Абстрактное сознание для Live: столб света и спиральные ленты. Ядро здесь не используется. */
class MindView(context: Context, private val skin: Skin) : View(context) {
    enum class Mood(val energy: Float, val speed: Float) {
        SLEEP(.08f, .16f), LISTEN(.45f, .55f), THINK(.3f, 1.9f), SPEAK(.95f, .8f),
    }

    var mood: Mood = Mood.SLEEP

    private class Ribbon(val offset: Float, val turns: Float, val radius: Float, val tone: Int)
    private class Mote(var u: Float, val a: Float, val r: Float, val s: Float)

    private val ribbons = List(8) { Ribbon(it / 8f * TAU, 2.2f + (it % 3) * .8f, .72f + (it % 4) * .14f, it % 3) }
    private val motes = List(90) { Mote(Random.nextFloat(), Random.nextFloat() * TAU, Random.nextFloat(), .05f + Random.nextFloat() * .16f) }
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = add }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val path = Path()
    private val oval = RectF()
    private var shaders: List<Shader> = emptyList()
    private var energy = .08f
    private var speed = .16f
    private var phase = 0f
    private var last = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val base = h * .9f
        val top = h * .02f
        shaders = listOf(skin.accent, skin.violet, skin.hot).map { tone ->
            LinearGradient(
                0f, base, 0f, top,
                intArrayOf(alpha(skin.cyan, .15f), alpha(tone, .9f), alpha(skin.violet, .8f), alpha(skin.accent, .1f)),
                floatArrayOf(0f, .35f, .75f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(.05f)
        last = now
        val t = now.toFloat()
        energy += (mood.energy - energy) * .05f
        speed += (mood.speed - speed) * .05f
        phase += dt * speed
        val beat = when (mood) {
            Mood.SPEAK -> abs(sin(t / 140f) * sin(t / 430f))
            Mood.LISTEN -> abs(sin(t / 320f)) * .5f
            Mood.THINK -> .15f
            Mood.SLEEP -> 0f
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * .5f
        val base = h * .9f
        val top = h * .02f
        val span = base - top
        val shrink = when (mood) { Mood.THINK -> .78f; Mood.SLEEP -> .62f; else -> 1f }
        val r = min(w * .34f, h * .3f) * shrink * (1 + beat * .12f)

        // Столб света
        val bw = 12f + energy * 34f + beat * 28f
        fill.shader = LinearGradient(cx - bw, 0f, cx + bw, 0f, intArrayOf(alpha(skin.accent, 0f), alpha(skin.cyan, .28f + energy * .3f), alpha(skin.accent, 0f)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(cx - bw, top, cx + bw, base, fill)
        fill.shader = null
        fill.color = alpha(Color.WHITE, .75f)
        canvas.drawRect(cx - 1.5f, top, cx + 1.5f, base, fill)

        // Ленты
        if (shaders.isNotEmpty()) ribbons.forEachIndexed { i, rb ->
            path.reset()
            for (k in 0..64) {
                val u = k / 64f
                val env = sin(PI.toFloat() * (u * 1.03f).coerceAtMost(1f)).coerceAtLeast(0f).pow(.72f)
                val wob = 1 + .22f * sin(u * 9 + phase * 2 + i)
                val ang = u * rb.turns * TAU + phase * (if (i % 2 == 1) 1.5f else -1.5f) + rb.offset
                val rad = r * rb.radius * env * wob
                val x = cx + cos(ang) * rad
                val y = base - u * span + sin(ang) * rad * .24f
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.shader = shaders[rb.tone]
            line.strokeWidth = context.dp(4f + energy * 3f).toFloat()
            line.alpha = 26
            canvas.drawPath(path, line)
            line.strokeWidth = context.dp(1f + energy * .7f).toFloat()
            line.alpha = 216
            canvas.drawPath(path, line)
        }
        line.shader = null

        // Искры
        motes.forEach { m ->
            m.u += m.s * dt * (.6f + speed)
            if (m.u > 1f) m.u = 0f
            val env = sin(PI.toFloat() * m.u)
            val a = m.a + m.u * 12 + phase
            val rad = r * .95f * env * m.r
            fill.color = alpha(if (m.r > .6f) skin.hot else skin.cyan, .35f + m.r * .6f)
            canvas.drawCircle(cx + cos(a) * rad, base - m.u * span + sin(a) * rad * .24f, context.dp(.8f + m.r * 1.4f).toFloat(), fill)
        }

        // Круги у основания
        for (i in 0 until 4) {
            val p = (t / 2600f + i / 4f) % 1f
            line.color = alpha(skin.cyan, (1 - p) * .6f)
            line.strokeWidth = 2f
            val rx = r * (.2f + p * 1.1f) * (1 + beat * .2f)
            val ry = r * (.05f + p * .2f)
            oval.set(cx - rx, base - ry, cx + rx, base + ry)
            canvas.drawOval(oval, line)
        }
        fill.shader = RadialGradient(cx, base, r * .5f, intArrayOf(alpha(Color.WHITE, .9f), alpha(skin.cyan, .5f), alpha(skin.accent, 0f)), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
        oval.set(cx - r * .5f, base - r * .13f, cx + r * .5f, base + r * .13f)
        canvas.drawOval(oval, fill)
        fill.shader = null
        if (isShown) postInvalidateOnAnimation()
    }

    private companion object { const val TAU = 6.2831855f }
}
