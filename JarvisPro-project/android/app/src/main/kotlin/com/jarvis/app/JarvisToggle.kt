package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class JarvisToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateAnimator()
            invalidate()
            onCheckedChanged?.invoke(value)
        }

    var onCheckedChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val trackWidth get() = dp(38f)
    private val trackHeight get() = dp(22f)
    private val knobSize get() = dp(16f)
    private val knobPadding get() = dp(2f)
    private val cyan = Color.parseColor("#62E9F4")
    private val knobOffColor = Color.parseColor("#8F9BAE")
    private val trackOffColor = Color.parseColor("#101820")
    private val borderColor = Color.parseColor("#3347EAFF")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = borderColor
    }
    private val onBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = cyan
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var knobX = 0f
    private val knobOff get() = knobPadding + knobSize / 2f
    private val knobOn get() = trackWidth - knobPadding - knobSize / 2f
    private var knobAnimator: ValueAnimator? = null

    init {
        setOnClickListener { isChecked = !isChecked }
    }

    private fun updateAnimator() {
        knobAnimator?.cancel()
        val target = if (isChecked) knobOn else knobOff
        knobAnimator = ValueAnimator.ofFloat(knobX, target).apply {
            duration = 160
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { knobX = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        knobX = if (isChecked) knobOn else knobOff
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackWidth.toInt(), trackHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cornerR = dp(2f)
        val trackRect = RectF(0f, 0f, trackWidth, trackHeight)
        trackPaint.color = if (isChecked) Color.parseColor("#3362E9F4") else trackOffColor
        canvas.drawRoundRect(trackRect, cornerR, cornerR, trackPaint)
        canvas.drawRoundRect(trackRect, cornerR, cornerR, borderPaint)
        if (isChecked) {
            canvas.drawRoundRect(trackRect, cornerR, cornerR, onBorderPaint)
        }
        knobPaint.color = if (isChecked) cyan else knobOffColor
        val knobRect = RectF(
            knobX - knobSize / 2f,
            trackHeight / 2f - knobSize / 2f,
            knobX + knobSize / 2f,
            trackHeight / 2f + knobSize / 2f,
        )
        canvas.drawRoundRect(knobRect, dp(1.5f), dp(1.5f), knobPaint)
    }
}
