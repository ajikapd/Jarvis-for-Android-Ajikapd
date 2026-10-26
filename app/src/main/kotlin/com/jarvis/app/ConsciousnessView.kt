package com.jarvis.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.jarvis.core.model.AssistantState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Volumetric consciousness ported from jarvis_mobile_ui startLiveCanvas(),
 * with additive bloom instead of a flat oval of dots.
 */
class ConsciousnessView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    var state: AssistantState = AssistantState.READY
    var level: Float = 0.2f
    var onTap: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val glow = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    private val ribbon = Path()
    private var running = false
    private val t0 = SystemClock.uptimeMillis()
    private val count = 1100
    private var yaw = 0f
    private var pitch = 0f
    private var waveAmp = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false

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
        waveAmp *= 0.94f
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) + abs(dy) > 6f) dragged = true
                yaw += dx * 0.012f
                pitch = (pitch + dy * 0.008f).coerceIn(-0.7f, 0.7f)
                waveAmp = 1f
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragged && event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val cx = width / 2f
        val cy = height * 0.44f
        val energy = when (state) {
            AssistantState.SPEAKING -> 1.12f
            AssistantState.THINKING, AssistantState.EXECUTING -> 1.25f
            AssistantState.LISTENING -> 0.95f + level
            else -> 0.74f
        }
        val beatHz = when (state) {
            AssistantState.SPEAKING -> 6f
            AssistantState.LISTENING -> 1.3f
            else -> 0.55f
        }
        val beat = (1f + 0.045f * sin(t * beatHz)) * energy
        val spin = if (state == AssistantState.THINKING || state == AssistantState.EXECUTING) 0.65f else 0.19f
        val session = when (state) {
            AssistantState.LISTENING, AssistantState.SPEAKING,
            AssistantState.THINKING, AssistantState.EXECUTING -> 1.15f
            else -> 1f
        }
        val bright = if (session > 1f) 1.18f else 1f
        val radius = width * 0.48f * session
        val cyan = Color.parseColor("#47EAFF")
        val indigo = Color.parseColor("#7C6BD6")
        val gold = Color.parseColor("#FFD28A")

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 2.1f,
            intArrayOf(Color.argb(50, 32, 160, 220), Color.argb(28, 20, 80, 140), Color.TRANSPARENT),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        paint.xfermode = add
        for (i in 0 until count) {
            val u = rand(i * 1.31f + 4f)
            val v = rand(i * 2.91f + 17f)
            val phi = acos((2f * u - 1f).coerceIn(-1f, 1f))
            val theta = v * PI.toFloat() * 2f + t * spin + yaw + (i % 8) * 0.012f
            val deform = 1f +
                0.10f * sin(phi * 5f + t * 1.2f) * cos(theta * 3f - t * 0.7f)
            val shell = radius * (0.94f + 0.06f * rand(i + 44f).pow(0.35f)) * deform * (0.96f + 0.04f * beat)
            var x = sin(phi) * cos(theta) * shell
            var y = cos(phi + pitch) * shell
            val z = sin(phi) * sin(theta) * shell
            val perspective = 1f / (1.15f - (z / radius) * 0.35f)
            x *= perspective
            y *= perspective
            val near = ((z / radius) + 1f) * 0.5f
            val flash = waveAmp * (0.55f + 0.45f * sin(phi * 7f - t * 9f))
            val sz = (0.9f + rand(i + 87f) * 2.4f) * (0.55f + near) * (1f + flash.coerceAtLeast(0f) * 0.7f)
            paint.color = when {
                i % 17 == 0 -> gold
                i % 5 == 0 -> indigo
                else -> cyan
            }
            paint.alpha = ((80 + near * 160) * bright + flash.coerceAtLeast(0f) * 90f).toInt().coerceIn(50, 255)
            canvas.drawCircle(cx + x, cy + y, sz, paint)
        }

        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.4f
        paint.maskFilter = glow
        for (k in 0 until 6) {
            ribbon.reset()
            for (s in 0..90) {
                val ang = s / 90f * PI.toFloat() * 2f
                val mod = 1f + 0.14f * sin(ang * (3f + k) + t * (if (state == AssistantState.THINKING) 3f else 0.9f) + k)
                val rr = radius * (0.88f + k * 0.018f) * mod
                val x = cx + cos(ang + yaw * 0.35f + t * (if (k % 2 == 0) 0.35f else -0.28f)) * rr
                val y = cy + sin(ang + pitch + k * 0.4f) * rr * (0.92f + k * 0.01f)
                if (s == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
            }
            paint.color = when (k % 3) {
                1 -> Color.argb(190, 160, 120, 255)
                2 -> Color.argb(170, 255, 210, 138)
                else -> Color.argb(210, 71, 234, 255)
            }
            canvas.drawPath(ribbon, paint)
        }
        paint.maskFilter = null
        paint.xfermode = null
        canvas.restoreToCount(layer)

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 0.42f,
            intArrayOf(Color.parseColor("#F4FEFF"), cyan, Color.argb(0, 71, 234, 255)),
            floatArrayOf(0f, 0.28f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.maskFilter = glow
        canvas.drawCircle(cx, cy, radius * 0.28f * beat, paint)
        paint.shader = null
        paint.maskFilter = null

        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(160, 236, 255, 255)
        paint.strokeWidth = 1.4f
        val arm = radius * 0.08f
        canvas.drawLine(cx - arm * 2, cy, cx - arm, cy, paint)
        canvas.drawLine(cx + arm, cy, cx + arm * 2, cy, paint)
        canvas.drawLine(cx, cy - arm * 2, cx, cy - arm, paint)
        canvas.drawLine(cx, cy + arm, cx, cy + arm * 2, paint)
    }

    private fun rand(seed: Float): Float {
        val x = sin(seed * 127.1f + 311.7f) * 43758.5453f
        return x - floor(x)
    }
}
