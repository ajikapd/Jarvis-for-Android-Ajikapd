package com.jarvis.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.app.JarvisSettings

/** Тема нового интерфейса: одна на все экраны, смена темы пересоздаёт окно. */
data class Skin(
    val id: String,
    val title: String,
    val bg: Int,
    val glass: Int,
    val line: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val cyan: Int,
    val hot: Int,
    val violet: Int,
    val onAccent: Int,
    val good: Int = Color.parseColor("#3FE59B"),
    val warn: Int = Color.parseColor("#FFB648"),
    val danger: Int = Color.parseColor("#FF4F45"),
    val light: Boolean = false,
    val metal: Int = Color.parseColor("#2B3A50"),
)

object Skins {
    private fun c(hex: String) = Color.parseColor(hex)
    val ALL = listOf(
        Skin("reactor", "Реактор", c("#040811"), c("#8F0B1C34"), c("#524696FF"), c("#ECF4FF"), c("#8FA8C6"), c("#35A6FF"), c("#00DFFF"), c("#FF8E30"), c("#8B42FF"), c("#03121F")),
        Skin("gold", "Золото", c("#0B0704"), c("#8F2C1D0C"), c("#52ECB258"), c("#F8EFE0"), c("#B9A486"), c("#EEB45A"), c("#FFD68C"), c("#FF7832"), c("#DC6E3C"), c("#1C1204"), metal = c("#3A2E20")),
        Skin("mark", "Марк", c("#0B0304"), c("#8F2E0C0E"), c("#57FF5C4C"), c("#FAECEA"), c("#BB9692"), c("#FF5A48"), c("#FFBE78"), c("#FFC446"), c("#FF5078"), c("#230503"), metal = c("#3A2426")),
        Skin("stealth", "Стелс", c("#050506"), c("#8F1C1D21"), c("#3DCDD2DE"), c("#EFF0F3"), c("#9A9CA6"), c("#D7DBE4"), c("#FFFFFF"), c("#AAB0BE"), c("#969CAC"), c("#111111"), metal = c("#2E2F35")),
        Skin("light", "Светлая", c("#E7EEF6"), c("#B8FFFFFF"), c("#421E5AAA"), c("#0E2036"), c("#587089"), c("#0A74E0"), c("#00AADC"), c("#E6781E"), c("#7846E6"), c("#FFFFFF"), good = c("#0C9A61"), light = true, metal = c("#22334A")),
    )

    fun current(context: Context): Skin {
        val id = JarvisSettings.prefs(context).getString("ui_skin", "reactor")
        return ALL.firstOrNull { it.id == id } ?: ALL[0]
    }

    fun set(context: Context, id: String) {
        JarvisSettings.prefs(context).edit().putString("ui_skin", id).apply()
    }
}

/** Шрифты лежат в assets: начертания берём из вариативных файлов. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(context: Context, file: String, weight: Int?): Typeface = synchronized(cache) {
        cache.getOrPut("$file$weight") {
            runCatching {
                val builder = Typeface.Builder(context.assets, "fonts/$file.ttf")
                if (weight != null) builder.setFontVariationSettings("'wght' $weight")
                builder.build()
            }.getOrNull() ?: Typeface.DEFAULT
        }
    }

    fun brand(context: Context) = load(context, "michroma", null)
    fun display(context: Context, weight: Int = 600) = load(context, "jura", weight)
    fun body(context: Context, weight: Int = 500) = load(context, "manrope", weight)
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.glassBg(skin: Skin, radius: Int = 16, fill: Int = skin.glass, line: Int = skin.line): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1).coerceAtLeast(1), line)
    }

fun alpha(color: Int, a: Float): Int = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

fun Context.label(
    text: CharSequence = "",
    size: Float = 14f,
    color: Int,
    face: Typeface = Fonts.body(this),
    gravity: Int = Gravity.START,
    lines: Int = Int.MAX_VALUE,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    typeface = face
    this.gravity = gravity
    maxLines = lines
    if (lines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
    setLineSpacing(0f, 1.2f)
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun Context.column(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) {
        dividerDrawable = GradientDrawable().apply { setSize(0, dp(gapDp)); setColor(Color.TRANSPARENT) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

fun Context.rowLayout(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) {
        dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 0); setColor(Color.TRANSPARENT) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

fun <T : View> T.pad(all: Int): T = apply { val p = context.dp(all); setPadding(p, p, p, p) }
fun <T : View> T.pad(h: Int, v: Int): T = apply { setPadding(context.dp(h), context.dp(v), context.dp(h), context.dp(v)) }
fun <T : View> T.tap(action: () -> Unit): T = apply {
    isClickable = true
    setOnClickListener {
        com.jarvis.app.HudFeedback.key(it)
        action()
    }
}
