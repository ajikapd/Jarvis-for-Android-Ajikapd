package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min

/** Compact HUD frame over an optional camera preview, with a scanning line. */
class HudViewfinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val texture = TextureView(context)
    private val overlay = ScanOverlay(context)
    private val flip = TextView(context)
    private var front = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        flip.text = "LENS"
        flip.setTextColor(Color.parseColor("#62E9F4"))
        flip.textSize = 8f
        flip.typeface = android.graphics.Typeface.MONOSPACE
        flip.setPadding(8, 4, 8, 4)
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        addView(flip, lp)
        flip.setOnClickListener {
            front = !front
            flip.text = if (front) "FRONT" else "REAR"
            openCamera()
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onDetachedFromWindow() {
        closeCamera()
        super.onDetachedFromWindow()
    }

    private fun openCamera() {
        val textureSurface = texture.surfaceTexture ?: return
        closeCamera()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { cam ->
            val facing = manager.getCameraCharacteristics(cam).get(CameraCharacteristics.LENS_FACING)
            if (front) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val surface = android.view.Surface(textureSurface)
                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(capture: CameraCaptureSession) {
                            session = capture
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }
                            capture.setRepeatingRequest(request.build(), null, handler)
                        }
                        override fun onConfigureFailed(capture: CameraCaptureSession) = Unit
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) { device.close() }
            }, handler)
        }
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
    }

    private class ScanOverlay(context: Context) : android.view.View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t0 = SystemClock.uptimeMillis()
        override fun onDraw(canvas: Canvas) {
            val t = ((SystemClock.uptimeMillis() - t0) % 1600) / 1600f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.parseColor("#3347EAFF")
            val step = width / 4f
            var x = step
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                x += step
            }
            var y = height / 5f
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, paint)
                y += height / 5f
            }
            paint.color = Color.parseColor("#62E9F4")
            paint.strokeWidth = 3f
            val a = min(width, height) * 0.16f
            canvas.drawLine(0f, 0f, a, 0f, paint)
            canvas.drawLine(0f, 0f, 0f, a, paint)
            canvas.drawLine(width.toFloat(), 0f, width - a, 0f, paint)
            canvas.drawLine(width.toFloat(), 0f, width.toFloat(), a, paint)
            canvas.drawLine(0f, height.toFloat(), a, height.toFloat(), paint)
            canvas.drawLine(0f, height.toFloat(), 0f, height - a, paint)
            canvas.drawLine(width.toFloat(), height.toFloat(), width - a, height.toFloat(), paint)
            canvas.drawLine(width.toFloat(), height.toFloat(), width.toFloat(), height - a, paint)
            paint.alpha = 140
            canvas.drawLine(0f, height * t, width.toFloat(), height * t, paint)
            paint.alpha = 255
            paint.style = Paint.Style.FILL
            paint.textSize = 18f
            canvas.drawText("VISION FEED", 8f, height - 22f, paint)
            postInvalidateOnAnimation()
        }
    }
}
