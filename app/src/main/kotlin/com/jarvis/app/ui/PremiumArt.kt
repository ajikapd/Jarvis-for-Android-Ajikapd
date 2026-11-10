package com.jarvis.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Экономия: системные анимации выключены или заряд ниже 20%. */
internal object Calm {
    fun noMotion(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    fun lowBattery(context: Context): Boolean = Device.battery(context) in 1 until 20
}

/**
 * Живой дымчатый фон премиум-стиля.
 *
 * Один раз (в фоне, на всё приложение) считается гладкая бесшовная текстура дыма 512×512:
 * шум с искривлением пространства, как в макете. Дальше видеокарта каждый кадр лишь
 * двигает три слоя этой текстуры с разной скоростью, масштабом и поворотом.
 * Слои накладываются полупрозрачно, поэтому дым «течёт» и меняет форму,
 * а не просто едет. Пикселей нет: текстура крупнее, чем растягивается.
 */
class SmokeView(context: Context, private val skin: Skin) : View(context) {
    /** Оттенок дыма: свет Джарвиса, цвет «думаю» или приглушённый «выключен». */
    var tint: Int = skin.accent
        set(v) { if (field != v) { field = v; invalidate() } }

    private val still = Calm.noMotion(context) || Calm.lowBattery(context)
    private val start = SystemClock.uptimeMillis()
    private val layer = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shade = Paint(Paint.DITHER_FLAG)
    private val m = Matrix()
    private var shader: BitmapShader? = null

    init {
        // Текстура читается сразу при создании экрана: к первому кадру она уже есть.
        Texture.get(context)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Затемнение к краям: панели читаются, середина живее.
        shade.shader = RadialGradient(
            w / 2f, h * .4f, maxOf(w, h) * .75f,
            intArrayOf(Color.TRANSPARENT, alpha(skin.bg, .35f), alpha(skin.bg, .85f)),
            floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(skin.bg)
        val tex = Texture.get(context)
        if (tex == null) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            return
        }
        val s = shader ?: BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { shader = it }
        layer.shader = s
        val t = if (still) 30f else (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val base = maxOf(w, h) / Texture.SIZE
        // Слой 1: тело дыма, большой и медленный, серо-белый с лёгким оттенком.
        draw(c, s, base * 1.35f, t * 9f, t * 5f, t * .6f, mix(Color.rgb(150, 156, 168), tint, .25f), .55f, w, h)
        // Слой 2: навстречу первому, в цвет схемы — из-за разницы хода дым «перетекает».
        draw(c, s, base * .95f, -t * 12f + 300f, t * 7f + 120f, -t * .9f + 40f, tint, .42f, w, h)
        // Слой 3: мелкие светлые пряди, быстрее остальных.
        draw(c, s, base * .62f, t * 16f + 700f, -t * 10f + 500f, t * 1.4f + 80f, mix(Color.WHITE, tint, .45f), .2f, w, h)
        c.drawRect(0f, 0f, w, h, shade)
        if (!still && isShown) postInvalidateDelayed(FRAME_MS)
    }

    private fun draw(c: Canvas, s: BitmapShader, scale: Float, dx: Float, dy: Float, deg: Float, color: Int, a: Float, w: Float, h: Float) {
        m.reset()
        m.setScale(scale, scale)
        m.postRotate(deg, w / 2, h / 2)
        m.postTranslate(dx, dy)
        s.setLocalMatrix(m)
        // Текстура хранит только плотность (ALPHA_8), цвет берётся из кисти.
        layer.color = color
        layer.alpha = (a * 255).toInt()
        c.drawRect(0f, 0f, w, h, layer)
    }

    /**
     * Текстура дыма: готовый файл assets/ui/smoke.png (его делает tools/gen_smoke_texture.py).
     * Читается один раз на всё приложение за миллисекунды, поэтому дым виден сразу при открытии.
     */
    internal object Texture {
        const val SIZE = 512
        @Volatile private var cached: Bitmap? = null

        fun get(context: Context): Bitmap? {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val loaded = runCatching {
                    context.assets.open("ui/smoke.png").use { input ->
                        val rgba = android.graphics.BitmapFactory.decodeStream(input)
                        // Нужна только плотность: альфа-канал, цвет даёт кисть.
                        rgba.extractAlpha().also { rgba.recycle() }
                    }
                }.getOrNull()
                cached = loaded
                return loaded
            }
        }
    }

    private companion object { const val FRAME_MS = 16L }
}


/** Ядро из частиц: дышит от голоса, закручивается в раздумьях, пульсирует, когда говорит. */
class ParticleCoreView(context: Context, private val skin: Skin, count: Int = 2600, private val filaments: Boolean = true) : View(context) {
    enum class Mode { OFF, IDLE, LISTEN, THINK, SPEAK }

    var mode = Mode.IDLE
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Какую долю меньшей стороны вида занимает радиус шара. */
    var scale = .3f

    /** false — один неподвижный кадр (например, ядра у старых ответов в диалоге). */
    var live = true
        set(v) { field = v; if (v) invalidate() }

    private val still = Calm.noMotion(context)
    private val n = if (Calm.lowBattery(context)) count / 2 else count
    // На точку: косинус широты, долгота, высота, свой ритм, широта.
    private val pts = FloatArray(n * 5).also { a ->
        for (i in 0 until n) {
            val y = 1 - 2 * (i + .5f) / n
            val r = sqrt(1 - y * y)
            val th = i * 2.399963f
            val lat = asin(y)
            a[i * 5] = cos(lat)
            a[i * 5 + 1] = atan2(sin(th) * r, cos(th) * r)
            a[i * 5 + 2] = y
            a[i * 5 + 3] = Random.nextFloat()
            a[i * 5 + 4] = lat
        }
    }
    private val links = List(n / 40) { Random.nextInt(n) to Random.nextInt(n) }
    private val proj = FloatArray(n * 3)
    private val buckets = Array(BUCKETS) { FloatArray(n * 2) }
    private val fill = IntArray(BUCKETS)
    private val sparks = FloatArray(n * 2)
    private val dot = Paint().apply { strokeCap = Paint.Cap.SQUARE; blendMode = BlendMode.PLUS }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; blendMode = BlendMode.PLUS }
    private val segs = FloatArray(links.size * 4)
    private val m = Matrix()
    private var halo: RadialGradient? = null
    private var heart: RadialGradient? = null
    private var shaderColor = 0
    private var ry = 0f
    private var amp = 0f
    private var swirl = 0f
    private var last = 0L
    private val start = SystemClock.uptimeMillis()

    /** Один неподвижный кадр картинкой: для виджета, где анимации нет. */
    fun snapshot(px: Int): Bitmap {
        amp = when (mode) { Mode.OFF -> 0f; Mode.IDLE -> .08f; Mode.LISTEN -> 1f; Mode.THINK -> .35f; Mode.SPEAK -> .8f }
        measure(MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY))
        layout(0, 0, px, px)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        return bmp
    }

    private fun color(): Int = when (mode) {
        Mode.THINK -> skin.violet
        Mode.OFF -> mix(skin.metal, skin.accent, .25f)
        else -> skin.accent
    }

    /** Градиенты создаются один раз на цвет, дальше только двигаются матрицей. */
    private fun shaders(acc: Int) {
        if (acc == shaderColor && halo != null) return
        shaderColor = acc
        halo = RadialGradient(0f, 0f, 1f, intArrayOf(alpha(acc, .33f), alpha(acc, .08f), Color.TRANSPARENT), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        heart = RadialGradient(0f, 0f, 1f, intArrayOf(alpha(Color.WHITE, .93f), alpha(acc, .67f), Color.TRANSPARENT), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(.1f)
        last = now
        val t = (now - start) / 1000f
        val target = when (mode) { Mode.OFF -> 0f; Mode.IDLE -> .08f; Mode.LISTEN -> 1f; Mode.THINK -> .35f; Mode.SPEAK -> .8f }
        amp += (target - amp) * .06f
        swirl += ((if (mode == Mode.THINK) 1f else 0f) - swirl) * .04f
        ry += dt * when (mode) { Mode.OFF -> .02f; Mode.IDLE -> .12f; Mode.LISTEN -> .25f; Mode.THINK -> .9f; Mode.SPEAK -> .3f }

        val cx = width / 2f
        val cy = height / 2f
        val r0 = minOf(width, height) * scale
        val acc = color()
        val off = mode == Mode.OFF
        shaders(acc)

        val hr0 = r0 * 1.6f
        m.setScale(hr0, hr0); m.postTranslate(cx, cy)
        halo!!.setLocalMatrix(m)
        glow.shader = halo
        glow.alpha = if (off) 110 else 255
        c.drawCircle(cx, cy, hr0, glow)

        val cyR = cos(ry); val syR = sin(ry); val ct = COS_TILT; val st = SIN_TILT
        val voice = amp * (.4f + .6f * abs(sin(t * 3.3f) * sin(t * 1.9f + 1)))
        fill.fill(0)
        var sparkCount = 0
        val speak = if (mode == Mode.SPEAK) amp * .06f else 0f
        val swirlK = swirl * sin(t * 1.5f) * 1.2f
        val t4 = t * 4; val t3 = t * 3; val t9 = t * 9; val t2 = t * 2
        val k15 = r0 * 1.5f
        for (i in 0 until n) {
            val o = i * 5
            val cl = pts[o]; val lon = pts[o + 1]; val yy = pts[o + 2]; val k = pts[o + 3]; val lat = pts[o + 4]
            val d = if (off) .55f + .03f * k else {
                1 + voice * .16f * sin(lat * 6 + t4) * cos(lon * 5 - t3) + (if (speak > 0f) speak * sin(t9 - abs(lat) * 6) else 0f)
            }
            val lo = lon + swirlK * (1 - abs(yy))
            val x = cl * cos(lo) * d
            val z = cl * sin(lo) * d
            val y = yy * d
            val bigX = x * cyR + z * syR
            var bigZ = -x * syR + z * cyR
            val bigY = y * ct - bigZ * st
            bigZ = y * st + bigZ * ct
            val pz = 1 / (1.9f - bigZ * .5f)
            val px = cx + bigX * k15 * pz
            val py = cy + bigY * k15 * pz
            val p = i * 3
            proj[p] = px; proj[p + 1] = py; proj[p + 2] = bigZ
            if (k > .93f && !off) {
                sparks[sparkCount++] = px; sparks[sparkCount++] = py
            } else {
                val a = (.18f + .41f * (bigZ + 1)) * (if (off) .45f else 1f) * (.55f + .45f * sin(t2 + k * 20))
                val b = (a * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                val arr = buckets[b]
                arr[fill[b]++] = px; arr[fill[b]++] = py
            }
        }
        dot.strokeWidth = if (width > 600) 2.6f else 1.8f
        for (b in 0 until BUCKETS) {
            if (fill[b] == 0) continue
            dot.color = alpha(acc, (b + .5f) / BUCKETS * .9f)
            c.drawPoints(buckets[b], 0, fill[b], dot)
        }
        if (sparkCount > 0) {
            dot.color = alpha(Color.WHITE, .8f)
            c.drawPoints(sparks, 0, sparkCount, dot)
        }
        if (!off && filaments && width > 400) {
            line.color = alpha(acc, .08f + .1f * amp)
            // Нити — прямые отрезки одним вызовом: кривые пришлось бы заново раскладывать каждый кадр.
            var k = 0
            links.forEach { (i, j) ->
                if (proj[i * 3 + 2] < -.2f || proj[j * 3 + 2] < -.2f) return@forEach
                segs[k++] = proj[i * 3]; segs[k++] = proj[i * 3 + 1]; segs[k++] = proj[j * 3]; segs[k++] = proj[j * 3 + 1]
            }
            if (k > 0) c.drawLines(segs, 0, k, line)
        }
        val hr = r0 * (if (off) .08f else .16f + voice * .06f) * 2.2f
        m.setScale(hr, hr); m.postTranslate(cx, cy)
        heart!!.setLocalMatrix(m)
        core.shader = heart
        core.alpha = if (off) 100 else 255
        c.drawCircle(cx, cy, hr, core)
        // 60 кадров достаточно: на экранах 120 Гц это вдвое меньше работы.
        if (!live) amp = target
        if (isShown && live && !(still && abs(amp - target) < .01f)) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val BUCKETS = 8
        const val FRAME_MS = 15L
        val COS_TILT = cos(.35f)
        val SIN_TILT = sin(.35f)
    }
}

/** Шкала-прибор вокруг ядра: неподвижные риски берутся из готовой картинки, каждый кадр рисуются только две дуги. */
class DialView(context: Context, private val skin: Skin) : View(context) {
    var tone: Int = skin.accent
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private var ticks: Bitmap? = null
    private val still = Calm.noMotion(context)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ticks?.recycle()
        ticks = null
        if (w == 0 || h == 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val cy = h / 2f
        val r = radius()
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.mono(context); textAlign = Paint.Align.CENTER
            textSize = r * .042f; color = alpha(Color.WHITE, .35f)
        }
        for (i in 0 until 180) {
            val a = i * 2 * Math.PI.toFloat() / 180
            val big = i % 15 == 0
            val r0 = if (big) r * .95f else r * .975f
            tp.strokeWidth = if (big) 1.6f else 1f
            tp.color = alpha(Color.WHITE, if (big) .5f else .14f)
            c.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r, cy + sin(a) * r, tp)
            if (big) c.drawText((i * 2).toString().padStart(3, '0'), cx + cos(a) * r * 1.05f, cy + sin(a) * r * 1.05f + txt.textSize / 3, txt)
        }
        tp.color = alpha(Color.WHITE, .07f)
        c.drawCircle(cx, cy, r * .873f, tp)
        ticks = bmp
    }

    private fun radius() = minOf(width, height) / 2f * .78f

    override fun onDraw(c: Canvas) {
        ticks?.let { c.drawBitmap(it, 0f, 0f, null) }
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        val t = SystemClock.uptimeMillis() / 1000f
        p.strokeWidth = 2.4f
        p.color = tone
        val ra = r * .873f
        oval.set(cx - ra, cy - ra, cx + ra, cy + ra)
        c.drawArc(oval, -90f + t * 12f, 52f, false, p)
        p.color = alpha(skin.hot, .8f)
        p.strokeWidth = 2f
        val rb = r * .91f
        oval.set(cx - rb, cy - rb, cx + rb, cy + rb)
        c.drawArc(oval, 90f - t * 7f, 29f, false, p)
        if (isShown && !still) postInvalidateDelayed(33L)
    }
}
