package com.jarvis.app

import android.graphics.Color
import com.jarvis.core.model.AssistantState

object CoreTheme {
    val BG = Color.parseColor("#0F0E14")
    val BG_DEEP = Color.parseColor("#08070B")
    val PANEL = Color.parseColor("#1A1822")
    val BORDER = Color.parseColor("#2E2B3A")
    val TEXT = Color.parseColor("#F2EEE6")
    val MUTED = Color.parseColor("#9A96A8")
    val FAINT = Color.parseColor("#635D71")
    val BRASS = Color.parseColor("#E3A542")
    val BRASS_DEEP = Color.parseColor("#8A6A2E")
    val TEAL = Color.parseColor("#4FB8AC")
    val TEAL_DEEP = Color.parseColor("#2A6F68")
    val VIOLET = Color.parseColor("#7C6BD6")
    val VIOLET_DEEP = Color.parseColor("#453B7A")
    val DANGER = Color.parseColor("#D9634A")
    val DANGER_DEEP = Color.parseColor("#7A2E1F")

    data class CoreColors(
        val core: Int,
        val deep: Int,
        val breatheMs: Long,
        val label: String,
        val glyph: GlyphMode,
    )

    enum class GlyphMode { OFF, WAVE_FULL, WAVE_SOFT, THINKING }

    fun forState(state: AssistantState, accent: Int = BRASS): CoreColors = when (state) {
        AssistantState.OFFLINE -> CoreColors(FAINT, Color.parseColor("#3A3644"), 3200L, "● OFFLINE", GlyphMode.OFF)
        AssistantState.STARTING -> CoreColors(accent, darken(accent), 1600L, "ЗАПУСК ЯДРА…", GlyphMode.OFF)
        AssistantState.READY, AssistantState.IDLE -> CoreColors(accent, darken(accent), 3600L, "готов · скажи «джарвис»", GlyphMode.OFF)
        AssistantState.LISTENING -> CoreColors(TEAL, TEAL_DEEP, 1800L, "слушаю…", GlyphMode.WAVE_FULL)
        AssistantState.THINKING -> CoreColors(VIOLET, VIOLET_DEEP, 2200L, "обрабатываю", GlyphMode.THINKING)
        AssistantState.EXECUTING -> CoreColors(VIOLET, VIOLET_DEEP, 1600L, "выполняю", GlyphMode.THINKING)
        AssistantState.SPEAKING -> CoreColors(accent, darken(accent), 2600L, "отвечаю", GlyphMode.WAVE_SOFT)
        AssistantState.ERROR -> CoreColors(DANGER, DANGER_DEEP, 2400L, "ошибка ядра", GlyphMode.OFF)
    }

    private fun darken(color: Int): Int {
        val f = 0.55f
        return Color.rgb(
            (Color.red(color) * f).toInt().coerceIn(0, 255),
            (Color.green(color) * f).toInt().coerceIn(0, 255),
            (Color.blue(color) * f).toInt().coerceIn(0, 255),
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}