package com.jarvis.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.PathParser

/** Значки: контуры в сетке 24×24, цвет задаётся при создании. */
class IconView(context: Context, data: String, var tint: Int, private val sizeDp: Int = 22) : View(context) {
    private val source: Path = PathParser.createPathFromPathData(data)
    private val scaled = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(sizeDp), context.dp(sizeDp))

    override fun onDraw(c: Canvas) {
        val k = width / 24f
        matrix.setScale(k, k)
        source.transform(matrix, scaled)
        paint.color = tint
        paint.strokeWidth = 1.7f * k
        c.drawPath(scaled, paint)
    }
}

object Ic {
    const val HOME = "M3 11 12 3l9 8M6 9.5V20h12V9.5"
    const val CHAT = "M4 5h16v11H9l-5 4z"
    const val BRAIN = "M9 4a3 3 0 0 0-3 3 3 3 0 0 0-2 5 3 3 0 0 0 2 5 3 3 0 0 0 6 1V6a2 2 0 0 0-3-2zM15 4a3 3 0 0 1 3 3 3 3 0 0 1 2 5 3 3 0 0 1-2 5 3 3 0 0 1-6 1"
    const val LIVE = "M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4"
    const val GRID = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
    const val DOTS = "M5 11a1 1 0 1 0 0 2 1 1 0 0 0 0-2zM12 11a1 1 0 1 0 0 2 1 1 0 0 0 0-2zM19 11a1 1 0 1 0 0 2 1 1 0 0 0 0-2z"
    const val MIC = "M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3"
    const val BACK = "M14 6l-6 6 6 6"
    const val SEND = "M5 12h14M13 6l6 6-6 6"
    const val BOLT = "M13 2 5 13h6l-1 9 9-12h-6z"
    const val CLOCK = "M12 3a9 9 0 1 0 9 9M12 7v5l3 2M21 3v5h-5"
    const val CHART = "M4 19h16M4 15l4-5 4 3 7-8"
    const val GLOBE = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"
    const val PHONE = "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A15 15 0 0 1 3 6a2 2 0 0 1 2-2z"
    const val MUSIC = "M9 18V6l10-2v12M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z"
    const val INFO = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5M12 8h.01"
    const val CAL = "M4 5h16v15H4zM4 10h16M8 3v4M16 3v4"
    const val SUN = "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 3v2M12 19v2M3 12h2M19 12h2"
    const val FACE = "M12 21a9 9 0 1 0-9-9c0 1.8.5 3.4 1.4 4.800L3 21l4.3-1.400A9 9 0 0 0 12 21zM9 10h.01M15 10h.01M9 14c1.7 1.3 4.3 1.3 6 0"
    const val CHIP = "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3M6 6h12v12H6zM10 10h4v4h-4z"
    const val PAINT = "M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2 0-1.5 1-2 2-2h2a3 3 0 0 0 3-3c0-6-4-11-9-11zM7.5 11h.01M10 7h.01M15 7h.01"
    const val BELL = "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4zM10 21h4"
    const val SHIELD = "M12 3 4 6v6c0 5 3.4 8 8 9 4.6-1 8-4 8-9V6zM9 12l2 2 4-4"
    const val HELP = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9.5 9a2.5 2.5 0 1 1 3.5 2.300c-.7.3-1 .8-1 1.700M12 17h.01"
    const val KEYS = "M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M8 14h8"
}
