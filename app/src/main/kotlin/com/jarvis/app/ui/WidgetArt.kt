package com.jarvis.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.jarvis.app.ReactorCoreView
import com.jarvis.app.ThemeApplier
import com.jarvis.core.model.AssistantState

/** Картинки для виджета в стиле выбранного интерфейса: фон по размеру виджета и ядро. */
object WidgetArt {
    private const val MAX_BG_W = 900

    /** Премиум: тёмное стекло с неподвижным дымом в цвет схемы, тонкая рамка и блик сверху. */
    fun premiumBackground(context: Context, skin: Skin, state: AssistantState, wDp: Int, hDp: Int): Bitmap {
        val (w, h, k) = size(context, wDp, hDp)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = 24 * context.resources.displayMetrics.density * k
        val box = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val clip = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        c.drawColor(skin.bg)
        val tint = if (state == AssistantState.OFFLINE || state == AssistantState.ERROR) mix(skin.metal, skin.accent, .45f) else stateTone(skin, state)
        SmokeView.Texture.get(context)?.let { tex ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val sh = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            p.shader = sh
            val base = maxOf(w, h) / SmokeView.Texture.SIZE.toFloat()
            val m = Matrix()
            fun layer(scale: Float, dx: Float, dy: Float, deg: Float, color: Int, a: Float) {
                m.setScale(base * scale, base * scale)
                m.postRotate(deg, w / 2f, h / 2f)
                m.postTranslate(dx, dy)
                sh.setLocalMatrix(m)
                p.color = color
                p.alpha = (a * 255).toInt()
                c.drawRect(box, p)
            }
            // Плашка маленькая, поэтому дым тише, чем в приложении: текст читается.
            layer(1.35f, 40f, -30f, 12f, mix(Color.rgb(150, 156, 168), tint, .25f), .32f)
            layer(.95f, -120f, 60f, -25f, tint, .26f)
            layer(.62f, 200f, 90f, 70f, mix(Color.WHITE, tint, .45f), .1f)
        }
        // Ядро «светит» на стекло слева, края темнее.
        val glow = Paint().apply {
            shader = RadialGradient(h * .55f, h / 2f, h * 1.1f, intArrayOf(alpha(tint, .22f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(box, glow)
        val shade = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(alpha(Color.WHITE, .06f), Color.TRANSPARENT, alpha(skin.bg, .55f)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(box, shade)
        c.restore()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * k
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), alpha(mix(Color.WHITE, skin.accent, .4f), .28f), alpha(skin.accent, .08f), Shader.TileMode.CLAMP)
        }
        val half = stroke.strokeWidth / 2
        c.drawRoundRect(RectF(half, half, w - half, h - half), r, r, stroke)
        return bmp
    }

    /** Классический: панель консоли в цветах выбранной темы классического интерфейса. */
    fun classicBackground(context: Context, palette: ThemeApplier.Palette, wDp: Int, hDp: Int): Bitmap {
        val (w, h, k) = size(context, wDp, hDp)
        val d = context.resources.displayMetrics.density * k
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = 14 * d
        val box = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), alpha(palette.bg2, .96f), alpha(palette.bg, .96f), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(box, r, r, fill)
        // Уголки-скобки, как у панелей консоли.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = palette.border }
        val half = d / 2
        c.drawRoundRect(RectF(half, half, w - half, h - half), r, r, line)
        line.color = palette.primary
        line.strokeWidth = 2 * d
        val l = 14 * d
        val o = 5 * d
        c.drawLine(o, o + r * .4f, o, o + r * .4f + l, line)
        c.drawLine(o + r * .4f, o, o + r * .4f + l, o, line)
        c.drawLine(w - o, h - o - r * .4f, w - o, h - o - r * .4f - l, line)
        c.drawLine(w - o - r * .4f, h - o, w - o - r * .4f - l, h - o, line)
        return bmp
    }

    /** Ядро из частиц для премиум-виджета. */
    fun particleCore(context: Context, skin: Skin, sizeDp: Int, state: AssistantState): Bitmap {
        val px = context.dp(sizeDp).coerceAtMost(360)
        val off = state == AssistantState.OFFLINE || state == AssistantState.ERROR
        return ParticleCoreView(context, skin, 900).apply {
            // Выключенное ядро сжимается вдвое — на виджете его иначе почти не видно.
            scale = if (off) .6f else .36f
            mode = when (state) {
                AssistantState.OFFLINE, AssistantState.ERROR -> ParticleCoreView.Mode.OFF
                AssistantState.LISTENING -> ParticleCoreView.Mode.LISTEN
                AssistantState.THINKING, AssistantState.EXECUTING -> ParticleCoreView.Mode.THINK
                AssistantState.SPEAKING -> ParticleCoreView.Mode.SPEAK
                else -> ParticleCoreView.Mode.IDLE
            }
        }.snapshot(px)
    }

    /** Ядро классического интерфейса — тот же реактор, что на его главном экране. */
    fun classicCore(context: Context, palette: ThemeApplier.Palette, sizeDp: Int, state: AssistantState): Bitmap {
        val px = context.dp(sizeDp).coerceAtMost(360)
        val view = ReactorCoreView(context).apply {
            this.state = state
            accent = palette.cyan
            hot = palette.primary
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, px, px)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** Размер картинки фона: по размеру виджета, но не больше [MAX_BG_W] по ширине. */
    private fun size(context: Context, wDp: Int, hDp: Int): Triple<Int, Int, Float> {
        val w = context.dp(wDp.coerceAtLeast(40))
        val h = context.dp(hDp.coerceAtLeast(40))
        val k = (MAX_BG_W.toFloat() / w).coerceAtMost(1f)
        return Triple((w * k).toInt(), (h * k).toInt(), k)
    }
}
