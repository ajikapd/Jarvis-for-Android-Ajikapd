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
import com.jarvis.app.UiMode

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
    /** Премиум-стиль: дымчатый фон, ядро из частиц, матовое стекло и моно-данные. */
    val premium: Boolean = false,
    /** Вторая точка градиента акцента (орб, переключатели). */
    val accent2: Int = cyan,
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

    /**
     * Цветовые схемы премиум-стиля. Схема окрашивает всё: фон, дым, стекло, линии,
     * второстепенный текст, ядро и орб. «Думаю» и второй акцент свои у каждой схемы.
     */
    val PREMIUM = listOf(
        premium("core", "Ядро", "#7FE8FF", "#3AA8FF", think = "#C4A8FF", hot = "#FFB36B"),
        premium("amber", "Янтарь", "#FFC98A", "#FF8E3C", think = "#FF9F7A", hot = "#7FE8FF"),
        premium("mark", "Марк", "#FF7A6B", "#E0342A", think = "#FFC46B", hot = "#FFD68C"),
        premium("aurora", "Аврора", "#8AF0C2", "#2FB98A", think = "#7FB8FF", hot = "#E8F58A"),
        premium("violet", "Фиолет", "#C4A8FF", "#7A52FF", think = "#7FE8FF", hot = "#FF8FD0"),
        premium("silver", "Серебро", "#E8EDF4", "#8D97A8", think = "#B8C4D8", hot = "#FFB36B"),
    )

    private fun premium(id: String, title: String, acc: String, acc2: String, think: String, hot: String): Skin {
        val a = c(acc)
        val a2 = c(acc2)
        val white = Color.WHITE
        return Skin(
            id, title,
            // Фон чуть подкрашен вторым акцентом: у каждой схемы своя «ночь».
            bg = mix(c("#040507"), a2, .07f),
            glass = alpha(mix(white, a, .35f), .075f),
            line = alpha(mix(white, a, .55f), .13f),
            text = mix(c("#EEF1F6"), a, .06f), muted = mix(c("#8F98A7"), a, .22f),
            accent = a, cyan = a, hot = c(hot), violet = c(think), onAccent = c("#0B0D12"),
            good = c("#7BE3B0"), warn = c("#FFB36B"), danger = c("#FF6B6B"),
            metal = mix(c("#6C7483"), a, .2f), premium = true, accent2 = a2,
        )
    }

    /** Схемы выбранного стиля. */
    fun list(context: Context): List<Skin> = if (UiMode.isPremium(context)) PREMIUM else ALL

    fun current(context: Context): Skin {
        if (UiMode.isPremium(context)) {
            val id = JarvisSettings.prefs(context).getString(PREMIUM_PREF, PREMIUM[0].id)
            return PREMIUM.firstOrNull { it.id == id } ?: PREMIUM[0]
        }
        val id = JarvisSettings.prefs(context).getString("ui_skin", "reactor")
        return ALL.firstOrNull { it.id == id } ?: ALL[0]
    }

    fun set(context: Context, id: String) {
        val key = if (PREMIUM.any { it.id == id }) PREMIUM_PREF else "ui_skin"
        JarvisSettings.prefs(context).edit().putString(key, id).apply()
        com.jarvis.app.JarvisWidgetProvider.refresh(context)
    }

    private const val PREMIUM_PREF = "ui_skin_premium"
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

    /** Премиум-стиль: заголовки Manrope, логотип Unbounded. Включается окном нового интерфейса. */
    @Volatile var premium = false

    fun brand(context: Context) = if (premium) logo(context) else load(context, "michroma", null)
    fun display(context: Context, weight: Int = 600) = if (premium) load(context, "manrope", weight.coerceAtLeast(600)) else load(context, "jura", weight)
    fun body(context: Context, weight: Int = 500) = load(context, "manrope", weight)
    /** Логотип и крупные цифры. */
    fun logo(context: Context, weight: Int = 700) = load(context, "unbounded", weight)
    /** Данные и подписи. */
    fun mono(context: Context, weight: Int = 400) = load(context, "jbmono", weight)
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.glassBg(skin: Skin, radius: Int = 16, fill: Int = skin.glass, line: Int = skin.line): GradientDrawable =
    if (skin.premium) {
        // Матовое стекло в цвет схемы: заливка светлеет к верху, радиусы мягче.
        val top = if (fill == skin.glass) alpha(skin.glass, Color.alpha(skin.glass) / 255f * 1.15f) else fill
        val bottom = if (fill == skin.glass) alpha(skin.glass, Color.alpha(skin.glass) / 255f * .45f) else fill
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp((radius * 1.35f).toInt().coerceAtMost(26).coerceAtLeast(radius)).toFloat()
            setStroke(dp(1).coerceAtLeast(1), line)
        }
    } else GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1).coerceAtLeast(1), line)
    }

/** Мелкая моно-подпись капсом: «LLM · 0.42 S». */
fun Context.monoLabel(text: CharSequence, size: Float, color: Int): TextView =
    label(text, size, color, Fonts.mono(this)).apply { letterSpacing = .14f; isAllCaps = true }

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
