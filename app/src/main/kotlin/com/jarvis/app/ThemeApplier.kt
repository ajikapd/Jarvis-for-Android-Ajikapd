package com.jarvis.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.WindowCompat

/**
 * Applies JarvisSettings.theme() across window + inflated view trees.
 * Layout XML still uses default cinematic tokens; this remaps colors at runtime.
 */
object ThemeApplier {

    data class Palette(
        val id: String,
        val bg: Int,
        val bg2: Int,
        val bg3: Int,
        val panel: Int,
        val panelSoft: Int,
        val border: Int,
        val text: Int,
        val text2: Int,
        val muted: Int,
        val faint: Int,
        val primary: Int,
        val primaryDark: Int,
        val cyan: Int,
        val green: Int,
        val danger: Int,
        val onPrimary: Int,
        val navBg: Int,
        val lightBars: Boolean,
    )

    fun palette(theme: String): Palette = when (theme) {
        "ARC" -> Palette(
            id = "ARC",
            bg = Color.parseColor("#020B12"),
            bg2 = Color.parseColor("#041822"),
            bg3 = Color.parseColor("#072433"),
            panel = Color.parseColor("#0A1C28"),
            panelSoft = Color.parseColor("#0D2433"),
            border = Color.parseColor("#1E4A5C"),
            text = Color.parseColor("#E8FBFF"),
            text2 = Color.parseColor("#C5E8F0"),
            muted = Color.parseColor("#7AA8B8"),
            faint = Color.parseColor("#3E6A7A"),
            primary = Color.parseColor("#62E9F4"),
            primaryDark = Color.parseColor("#2FB9CA"),
            cyan = Color.parseColor("#9AF6FF"),
            green = Color.parseColor("#61D89D"),
            danger = Color.parseColor("#F05D52"),
            onPrimary = Color.parseColor("#021018"),
            navBg = Color.parseColor("#01080E"),
            lightBars = false,
        )
        "STEALTH" -> Palette(
            id = "STEALTH",
            bg = Color.parseColor("#0A0A0C"),
            bg2 = Color.parseColor("#121216"),
            bg3 = Color.parseColor("#1A1A20"),
            panel = Color.parseColor("#14141A"),
            panelSoft = Color.parseColor("#1C1C24"),
            border = Color.parseColor("#2C2C36"),
            text = Color.parseColor("#ECECF0"),
            text2 = Color.parseColor("#C8C8D0"),
            muted = Color.parseColor("#8A8A98"),
            faint = Color.parseColor("#555566"),
            primary = Color.parseColor("#A8B0BC"),
            primaryDark = Color.parseColor("#7E8694"),
            cyan = Color.parseColor("#8EC8D4"),
            green = Color.parseColor("#6BCB8F"),
            danger = Color.parseColor("#E07070"),
            onPrimary = Color.parseColor("#101014"),
            navBg = Color.parseColor("#070709"),
            lightBars = false,
        )
        "LIGHT" -> Palette(
            id = "LIGHT",
            bg = Color.parseColor("#F4F1EA"),
            bg2 = Color.parseColor("#EBE6DC"),
            bg3 = Color.parseColor("#E0DAD0"),
            panel = Color.parseColor("#FFFFFF"),
            panelSoft = Color.parseColor("#F8F5EF"),
            border = Color.parseColor("#D5CFC4"),
            text = Color.parseColor("#1A1F28"),
            text2 = Color.parseColor("#2C3340"),
            muted = Color.parseColor("#5A6574"),
            faint = Color.parseColor("#8A93A1"),
            primary = Color.parseColor("#C4892A"),
            primaryDark = Color.parseColor("#A06E1E"),
            cyan = Color.parseColor("#2A8A9A"),
            green = Color.parseColor("#2F8F5B"),
            danger = Color.parseColor("#C4453B"),
            onPrimary = Color.parseColor("#1A1408"),
            navBg = Color.parseColor("#E8E2D6"),
            lightBars = true,
        )
        else -> Palette(
            id = "CLASSIC",
            bg = Color.parseColor("#03060B"),
            bg2 = Color.parseColor("#07101A"),
            bg3 = Color.parseColor("#0B1522"),
            panel = Color.parseColor("#0A131F"),
            panelSoft = Color.parseColor("#0D1A28"),
            border = Color.parseColor("#2A3A4C"),
            text = Color.parseColor("#F7F8FB"),
            text2 = Color.parseColor("#DCE4EE"),
            muted = Color.parseColor("#8F9BAE"),
            faint = Color.parseColor("#4D5A6D"),
            primary = Color.parseColor("#F3BB58"),
            primaryDark = Color.parseColor("#D99738"),
            cyan = Color.parseColor("#62E9F4"),
            green = Color.parseColor("#61D89D"),
            danger = Color.parseColor("#F05D52"),
            onPrimary = Color.parseColor("#17130B"),
            navBg = Color.parseColor("#03080D"),
            lightBars = false,
        )
    }

    fun applyToActivity(activity: Activity) {
        val p = palette(JarvisSettings.theme(activity))
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        activity.window.statusBarColor = p.bg2
        activity.window.navigationBarColor = p.navBg
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars =
            p.lightBars
        activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(p.bg)
        activity.findViewById<View>(R.id.dashboardRoot)?.setBackgroundColor(p.bg)
        activity.findViewById<View>(R.id.shellTop)?.setBackgroundColor(p.bg2)
        activity.findViewById<View>(R.id.bottomNav)?.setBackgroundColor(p.navBg)
        activity.findViewById<TextView>(R.id.statusTitle)?.setTextColor(p.text)
        activity.findViewById<TextView>(R.id.topRuntimeState)?.setTextColor(p.green)
    }

    fun applyToScreen(root: View, theme: String = JarvisSettings.theme(root.context)) {
        val p = palette(theme)
        walk(root, p)
    }

    fun recreateWithTheme(activity: Activity) {
        activity.recreate()
    }

    fun primaryColor(theme: String): Int = palette(theme).primary
    fun mutedColor(theme: String): Int = palette(theme).muted
    fun bgColor(theme: String): Int = palette(theme).bg
    fun textColor(theme: String): Int = palette(theme).text

    fun personaSystemHint(persona: String): String = when (persona) {
        "FRIENDLY" ->
            "Ты JARVIS в дружелюбном режиме: теплый, живой, с лёгким юмором, " +
                "обращайся на «ты», короткие живые фразы, без канцелярита."
        "MINIMAL" ->
            "Ты JARVIS в минимальном режиме: максимум 1 короткое предложение, " +
                "без воды, без эмодзи, только суть."
        "DEVELOPER" ->
            "Ты JARVIS в developer-режиме: технично, точно, с терминами, " +
                "упоминай статусы/ошибки если уместно, без сюсюканья."
        else ->
            "Ты JARVIS Classic: спокойный, вежливый, собранный дворецкий. " +
                "Чёткие ответы, лёгкая сдержанность, без панибратства."
    }

    private fun walk(view: View, p: Palette) {
        val bg = view.background
        // Solid dark backgrounds → theme panel/bg
        val tag = view.tag as? String
        when {
            tag == "theme_panel" -> view.background = rounded(p.panel, p.border, 18f)
            tag == "theme_soft" -> view.background = rounded(p.panelSoft, p.border, 14f)
            tag == "theme_card" -> view.background = rounded(p.bg3, p.border, 14f)
            tag == "theme_primary_btn" -> view.background = rounded(p.primary, p.primary, 13f)
            tag == "theme_ghost_btn" -> view.background = rounded(p.bg3, p.border, 13f)
            tag == "theme_chip" -> view.background = rounded(p.bg3, p.border, 999f)
        }

        when (view) {
            is EditText -> {
                view.setTextColor(p.text)
                view.setHintTextColor(p.faint)
            }
            is Button -> {
                val c = view.currentTextColor
                if (isNear(c, Color.parseColor("#17130B")) || isNear(c, Color.parseColor("#1A1408")) ||
                    isNear(c, Color.parseColor("#0A0A0D"))
                ) {
                    view.setTextColor(p.onPrimary)
                    view.background = rounded(p.primary, p.primary, 13f)
                } else if (isNear(c, Color.parseColor("#9A96A8")) || isNear(c, Color.parseColor("#8F9BAE"))) {
                    view.setTextColor(p.muted)
                }
            }
            is TextView -> remapText(view, p)
        }

        // Root-ish containers with hardcoded dark fills
        if (view is ViewGroup) {
            val solid = extractSolidColor(view)
            if (solid != null && isDarkish(solid)) {
                // only replace near-black cinematic backgrounds
                if (isNear(solid, Color.parseColor("#03060B")) ||
                    isNear(solid, Color.parseColor("#0F0E14")) ||
                    isNear(solid, Color.parseColor("#0A0C10")) ||
                    isNear(solid, Color.parseColor("#08070B"))
                ) {
                    view.setBackgroundColor(p.bg)
                } else if (
                    isNear(solid, Color.parseColor("#07101A")) ||
                    isNear(solid, Color.parseColor("#1A1822")) ||
                    isNear(solid, Color.parseColor("#0A131F"))
                ) {
                    view.setBackgroundColor(p.bg2)
                }
            }
            for (i in 0 until view.childCount) walk(view.getChildAt(i), p)
        }
    }

    private fun remapText(tv: TextView, p: Palette) {
        val c = tv.currentTextColor
        when {
            isNear(c, Color.parseColor("#F3BB58")) ||
                isNear(c, Color.parseColor("#E3A542")) ||
                isNear(c, Color.parseColor("#FFD47B")) ||
                isNear(c, Color.parseColor("#D99738")) -> tv.setTextColor(p.primary)
            isNear(c, Color.parseColor("#62E9F4")) ||
                isNear(c, Color.parseColor("#2FB9CA")) ||
                isNear(c, Color.parseColor("#4FB8AC")) -> tv.setTextColor(p.cyan)
            isNear(c, Color.parseColor("#61D89D")) -> tv.setTextColor(p.green)
            isNear(c, Color.parseColor("#F05D52")) ||
                isNear(c, Color.parseColor("#D9634A")) ||
                isNear(c, Color.parseColor("#E46B58")) -> tv.setTextColor(p.danger)
            isNear(c, Color.parseColor("#F7F8FB")) ||
                isNear(c, Color.parseColor("#F2EEE6")) ||
                isNear(c, Color.parseColor("#F4F0E7")) ||
                isNear(c, Color.parseColor("#E8FBFF")) -> tv.setTextColor(p.text)
            isNear(c, Color.parseColor("#DCE4EE")) ||
                isNear(c, Color.parseColor("#C5E8F0")) -> tv.setTextColor(p.text2)
            isNear(c, Color.parseColor("#8F9BAE")) ||
                isNear(c, Color.parseColor("#9A96A8")) ||
                isNear(c, Color.parseColor("#9DA5AE")) -> tv.setTextColor(p.muted)
            isNear(c, Color.parseColor("#4D5A6D")) ||
                isNear(c, Color.parseColor("#635D71")) ||
                isNear(c, Color.parseColor("#626B76")) -> tv.setTextColor(p.faint)
            isNear(c, Color.parseColor("#17130B")) ||
                isNear(c, Color.parseColor("#1A1408")) -> tv.setTextColor(p.onPrimary)
        }
    }

    fun rounded(fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * 3f // approx; density-corrected by caller ideally
            setColor(fill)
            setStroke((strokeDp * 3f).toInt().coerceAtLeast(1), stroke)
        }

    fun roundedDp(view: View, fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float = 1f): GradientDrawable {
        val d = view.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(fill)
            setStroke((strokeDp * d).toInt().coerceAtLeast(1), stroke)
        }
    }

    private fun extractSolidColor(view: View): Int? {
        val bg = view.background ?: return null
        return try {
            val field = bg.javaClass.methods.firstOrNull { it.name == "getColor" && it.parameterCount == 0 }
            field?.invoke(bg) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDarkish(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return (r + g + b) / 3 < 80
    }

    private fun isNear(a: Int, b: Int, tol: Int = 18): Boolean {
        return kotlin.math.abs(Color.red(a) - Color.red(b)) <= tol &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) <= tol &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= tol
    }
}
