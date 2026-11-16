package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Компактный слайдер в стиле Jarvis (без тяжёлого AppCompat SeekBar).
 * Значение в [0f..1f]; при изменении вызывает [onValueChanged].
 */
class JarvisSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var value: Float = 0.6f
        set(v) {
            val clamped = v.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
                onValueChanged?.invoke(clamped)
            }
        }

    var onValueChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val trackHeight = dp(3f)
    private val knobRadius = dp(9f)
    private val goldColor = Color.parseColor("#E0AD55")
    private val trackOffColor = Color.parseColor("#202832")
    private val borderColor = Color.parseColor("#2B333D")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = goldColor
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = borderColor
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(200f).toInt(), widthMeasureSpec)
        val h = resolveSize((knobRadius * 2 + dp(8f)).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private fun knobX(): Float {
        val usable = (width - knobRadius * 2).toFloat()
        return knobRadius + value * usable
    }

    private fun setFromTouch(x: Float) {
        val usable = (width - knobRadius * 2).toFloat()
        val raw = (x - knobRadius) / usable
        value = raw.coerceIn(0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performClick()
                setFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                setFromTouch(event.x)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = knobRadius
        val right = width - knobRadius

        // подложка
        trackPaint.color = trackOffColor
        canvas.drawRoundRect(RectF(left, cy - trackHeight / 2, right, cy + trackHeight / 2), trackHeight, trackHeight, trackPaint)
        canvas.drawRoundRect(RectF(left, cy - trackHeight / 2, right, cy + trackHeight / 2), trackHeight, trackHeight, borderPaint)

        // заполненная часть
        val kx = knobX()
        if (kx > left) {
            donePaint.color = goldColor
            canvas.drawRoundRect(RectF(left, cy - trackHeight / 2, kx, cy + trackHeight / 2), trackHeight, trackHeight, donePaint)
        }

        // ступица
        canvas.drawCircle(kx, cy, knobRadius, knobPaint)
        canvas.drawCircle(kx, cy, knobRadius * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1822") })
    }
}