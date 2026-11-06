package com.jarvis.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** Реактор картинкой для виджета: система не даёт ему анимацию, поэтому он меняется вместе с состоянием. */
object ReactorArt {
    private const val TAU = 6.2831855f

    fun render(context: Context, skin: Skin, sizeDp: Int, on: Boolean, loud: Boolean): Bitmap {
        val size = (context.dp(sizeDp) * 1).coerceAtMost(MAX_PX)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = size * .44f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val box = RectF()
        fun arc(radius: Float, a: Float, sweep: Float, color: Int, width: Float) {
            p.color = color
            p.strokeWidth = width
            box.set(cx - radius, cx - radius, cx + radius, cx + radius)
            c.drawArc(box, Math.toDegrees(a.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false, p)
        }
        val power = if (on) 1f else .12f
        val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
        fill.shader = RadialGradient(cx, cx, r * 1.13f, intArrayOf(alpha(skin.accent, .5f * power), alpha(skin.accent, 0f)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r * 1.13f, fill)
        for (i in 0 until 10) arc(r * .88f, i * TAU / 10 + .15f, .42f, if (on) skin.metal else alpha(skin.metal, .7f), r * .1f)
        p.xfermode = add
        for (i in 0 until 5) arc(r * .72f, i * TAU / 5 + .5f, .5f, alpha(skin.hot, .75f * power), (r * .03f).coerceAtLeast(1f))
        for (i in 0 until 18) {
            val extra = if (loud && i % 3 == 0) .3f else 0f
            arc(r * .6f, i * TAU / 18, .24f, alpha(skin.accent, ((.35f + .5f * power + extra) * (if (on) 1f else .35f)).coerceAtMost(1f)), r * .07f)
        }
        for (i in 0 until 10) arc(r * .4f, i * TAU / 10 + .1f, .42f, alpha(skin.cyan, .6f * power + .08f), r * .11f)
        p.xfermode = null
        val core = r * (.2f + .1f * power)
        fill.shader = RadialGradient(
            cx, cx, core * 1.5f,
            intArrayOf(alpha(Color.WHITE, if (on) 1f else .4f), alpha(skin.cyan, if (on) .95f else .3f), alpha(skin.accent, 0f)),
            floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cx, core * 1.5f, fill)
        return bmp
    }

    private const val MAX_PX = 300
}
