package com.jarvis.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Звуковая волна на панели голоса. */
class WaveView(context: Context, private val skin: Skin) : View(context) {
    var level = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis().toFloat()
        val n = 34
        val bw = width.toFloat() / n
        val mid = height / 2f
        for (i in 0 until n) {
            val e = sin(Math.PI.toFloat() * i / (n - 1))
            val b = 2 + e * height * .46f * level * (.35f + .65f * abs(sin(t / 170f + i * .7f) * sin(t / 390f + i * .23f)))
            paint.color = alpha(skin.cyan, .45f + e * .5f)
            c.drawRect(i * bw, mid - b, i * bw + (bw * .42f).coerceAtLeast(1.6f), mid + b, paint)
        }
        if (isShown) postInvalidateDelayed(33L)
    }
}

/** Круговой индикатор: подпись и значение внутри. */
class RingView(context: Context, private val skin: Skin, private val caption: String) : View(context) {
    var percent = 0
        set(v) { field = v; invalidate() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = context.dp(6).toFloat() }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.text; textAlign = Paint.Align.CENTER; typeface = Fonts.display(context); textSize = context.dp(17).toFloat() }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.muted; textAlign = Paint.Align.CENTER; typeface = Fonts.body(context); textSize = context.dp(10.5f).toFloat() }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val m = ring.strokeWidth
        val s = minOf(width, height).toFloat()
        val x = (width - s) / 2
        val y = (height - s) / 2
        box.set(x + m, y + m, x + s - m, y + s - m)
        ring.color = alpha(skin.text, .1f)
        c.drawArc(box, 0f, 360f, false, ring)
        ring.color = skin.accent
        c.drawArc(box, -90f, 3.6f * percent, false, ring)
        c.drawText("$percent%", width / 2f, height / 2f + context.dp(2), big)
        c.drawText(caption, width / 2f, height / 2f + context.dp(16), small)
    }
}

/** Вращающийся глобус на экране анализа. */
class GlobeView(context: Context, private val skin: Skin) : View(context) {
    private val land = buildList {
        repeat(620) {
            val la = asin(2 * Random.nextFloat() - 1)
            val lo = Random.nextFloat() * 6.2832f
            if (sin(lo * 2.3f + 1) + cos(la * 3.4f) + sin(lo * 5 + la * 4) * .5f > .45f) add(la to lo)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 7000f
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * .44f
        grid.color = alpha(skin.accent, .3f)
        for (i in -3..3) {
            val la = i / 4f * 1.57f
            val rx = cos(la) * r
            val y = cy + sin(la) * r
            oval.set(cx - rx, y - rx * .12f, cx + rx, y + rx * .12f)
            c.drawOval(oval, grid)
        }
        for (i in 0 until 6) {
            val k = abs(cos(i / 6f * 3.1416f + t)) * r
            oval.set(cx - k, cy - r, cx + k, cy + r)
            c.drawOval(oval, grid)
        }
        land.forEach { (la, lo) ->
            val a = lo + t
            val z = cos(la) * cos(a)
            if (z >= 0) {
                paint.color = alpha(skin.cyan, .3f + z * .6f)
                c.drawRect(cx + cos(la) * sin(a) * r, cy + sin(la) * r, cx + cos(la) * sin(a) * r + 3, cy + sin(la) * r + 3, paint)
            }
        }
        grid.color = alpha(skin.cyan, .8f)
        c.drawCircle(cx, cy, r, grid)
        if (isShown) postInvalidateDelayed(33L)
    }
}

/** Переключатель в стиле панелей. */
class SwitchView(context: Context, private val skin: Skin, on: Boolean) : View(context) {
    var checked = on
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(44), context.dp(25))

    override fun onDraw(c: Canvas) {
        box.set(1f, 1f, width - 1f, height - 1f)
        val r = height / 2f
        p.style = Paint.Style.FILL
        p.color = if (checked) alpha(skin.accent, .42f) else alpha(skin.text, .08f)
        c.drawRoundRect(box, r, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = if (checked) skin.accent else skin.line
        c.drawRoundRect(box, r, r, p)
        p.style = Paint.Style.FILL
        p.color = if (checked) (if (skin.light) skin.accent else Color.WHITE) else skin.muted
        c.drawCircle(if (checked) width - r else r, r, r * .68f, p)
    }
}
