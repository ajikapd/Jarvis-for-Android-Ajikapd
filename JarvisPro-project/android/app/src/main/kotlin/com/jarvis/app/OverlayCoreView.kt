package com.jarvis.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.jarvis.core.model.AssistantState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 56dp floating core: ring speed and color follow the voice pipeline. */
class OverlayCoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    var assistantState: AssistantState = AssistantState.READY

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    private var running = false
    private val t0 = SystemClock.uptimeMillis()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.36f
        val (ring, spin, pulse) = when (assistantState) {
            AssistantState.LISTENING -> Triple(Color.parseColor("#7AF6FF"), 1.4f, 0.08f)
            AssistantState.THINKING, AssistantState.EXECUTING -> Triple(Color.parseColor("#F3BB58"), 3.2f, 0.04f)
            AssistantState.SPEAKING -> Triple(Color.parseColor("#62E9F4"), 0.8f, 0.16f + 0.08f * sin(t * 8f))
            else -> Triple(Color.parseColor("#62E9F4"), 0.45f, 0.04f)
        }
        val beat = 1f + pulse * sin(t * if (assistantState == AssistantState.SPEAKING) 7f else 2f)
        paint.style = Paint.Style.FILL
        paint.maskFilter = glow
        paint.color = ring
        paint.alpha = 70
        canvas.drawCircle(cx, cy, r * 1.35f * beat, paint)
        paint.maskFilter = null
        paint.alpha = 255
        paint.shader = SweepGradient(cx, cy, intArrayOf(ring, Color.TRANSPARENT, ring), floatArrayOf(0f, 0.55f, 1f))
        canvas.save()
        canvas.rotate(t * 80f * spin, cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.22f * beat, paint)
        paint.color = ring
        paint.alpha = 180
        canvas.drawCircle(cx + cos(t * spin) * r * 0.72f, cy + sin(t * spin) * r * 0.72f, 3.2f, paint)
    }
}
