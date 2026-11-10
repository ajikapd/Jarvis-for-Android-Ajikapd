package com.jarvis.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.jarvis.app.ActivityFeed
import com.jarvis.app.AssistantCapabilities
import com.jarvis.app.BrainGraph
import com.jarvis.app.ConversationStore
import com.jarvis.app.VoiceController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun dm(ms: Long) = SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ms))

/** Скруглённая форма с разными углами: TL, TR, BR, BL в dp. */
private fun Context.corners(tl: Int, tr: Int, br: Int, bl: Int) = floatArrayOf(
    dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(),
    dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat(),
)

/** Серебряная заливка: пузыри человека и главные кнопки. */
private fun silver(radii: FloatArray) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, Color.parseColor("#D9DFE8")),
).apply { cornerRadii = radii }

/** Ядро из частиц вместо аватара: то же, что на главной, только маленькое. */
internal fun Context.miniCore(skin: Skin, sizeDp: Int, live: Boolean, mode: ParticleCoreView.Mode = ParticleCoreView.Mode.IDLE): ParticleCoreView =
    ParticleCoreView(this, skin, if (sizeDp >= 80) 1400 else 260, filaments = sizeDp >= 80).apply {
        scale = .38f
        this.mode = mode
        this.live = live
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
    }

internal fun coreMode(state: com.jarvis.core.model.AssistantState): ParticleCoreView.Mode = when (state) {
    com.jarvis.core.model.AssistantState.OFFLINE, com.jarvis.core.model.AssistantState.ERROR -> ParticleCoreView.Mode.OFF
    com.jarvis.core.model.AssistantState.LISTENING -> ParticleCoreView.Mode.LISTEN
    com.jarvis.core.model.AssistantState.THINKING, com.jarvis.core.model.AssistantState.EXECUTING -> ParticleCoreView.Mode.THINK
    com.jarvis.core.model.AssistantState.SPEAKING -> ParticleCoreView.Mode.SPEAK
    else -> ParticleCoreView.Mode.IDLE
}

private fun Context.glowDot(color: Int): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    elevation = 0f
}

// ───────────────────────────── Диалог ─────────────────────────────

/** Диалог премиум-стиля: пузыри, подпись Джарвиса со временем и трасса инструмента под ответом. */
class PremiumChatScreen(host: JarvisActivity) : Screen(host) {
    private val list = host.column(10)
    private val scroller = scroll(list) as ScrollView
    private val input = EditText(host).apply {
        hint = "Написать Джарвису…"
        setHintTextColor(skin.muted)
        setTextColor(skin.text)
        textSize = 14f
        typeface = Fonts.body(host, 500)
        background = null
        setPadding(0, 0, 0, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, _, _ -> submit(); true }
    }

    override fun meta(): String = "СЕССИЯ · ${ConversationStore.all().size} РЕПЛИК · ${if (host.isOn) "ОНЛАЙН" else "ВЫКЛЮЧЕН"}"

    private val head = FrameLayout(host)
    private var lastCore: ParticleCoreView? = null

    override fun onState(state: com.jarvis.core.model.AssistantState) {
        lastCore?.mode = coreMode(state)
    }

    override val view: View = host.column().apply {
        addView(head)
        addView(scroller, lp(h = 0, weight = 1f))
        addView(chips(), lp().apply { setMargins(host.dp(14), host.dp(6), 0, 0) })
        addView(composer(), lp(h = host.dp(52)).apply { setMargins(host.dp(14), host.dp(8), host.dp(14), host.dp(30)) })
    }

    private fun chips(): View {
        val row = host.rowLayout(6)
        listOf("Что ещё на сегодня?", "Повтори", "Отмени").forEach { text ->
            row.addView(host.label(text, 12f, skin.text, Fonts.body(host, 500), lines = 1).apply {
                setPadding(host.dp(12), host.dp(7), host.dp(12), host.dp(7))
                background = host.glassBg(skin, 16).apply { cornerRadius = host.dp(16).toFloat() }
                tap { host.send(text) }
            })
        }
        return HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    private fun composer(): View {
        val row = host.rowLayout(10).apply {
            setPadding(host.dp(16), host.dp(6), host.dp(6), host.dp(6))
            background = host.glassBg(skin, 26).apply { cornerRadius = host.dp(26).toFloat() }
        }
        row.addView(input, lp(0, -2, 1f))
        row.addView(FrameLayout(host).apply {
            contentDescription = "Сказать голосом"
            addView(IconView(host, Ic.MIC, skin.muted, 20), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            tap {
                if (host.isOn) VoiceController.manualWake() else host.powerOn()
                host.show(JarvisActivity.HOME)
            }
        }, lp(host.dp(36), host.dp(40)))
        row.addView(FrameLayout(host).apply {
            contentDescription = "Отправить"
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.WHITE, Color.parseColor("#CFD6E0"))).apply { shape = GradientDrawable.OVAL }
            addView(IconView(host, ARROW_UP, skin.onAccent, 20), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            tap { submit() }
        }, lp(host.dp(40), host.dp(40)))
        return row
    }

    private fun submit() {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) return
        input.setText("")
        host.send(text)
    }

    private fun mine(b: ConversationStore.Bubble): View {
        val box = host.column(3).apply {
            setPadding(host.dp(14), host.dp(11), host.dp(14), host.dp(8))
            background = silver(host.corners(20, 20, 6, 20))
        }
        // Ширина по содержимому (не MATCH_PARENT): иначе вторая мерка столбца обрезает высоту текста.
        box.addView(host.label(b.text, 13.5f, skin.onAccent, Fonts.body(host, 500)).apply { maxWidth = maxBubble() }, lp(-2, -2))
        box.addView(host.label(hm(b.atMs), 9f, Color.parseColor("#5D6574"), Fonts.mono(host), Gravity.END), lp(-2, -2).apply { gravity = Gravity.END })
        return FrameLayout(host).apply { addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.END)) }
    }

    private fun his(b: ConversationStore.Bubble, askedAt: Long?, last: Boolean): View {
        val box = host.column(6).apply {
            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
            background = host.glassBg(skin, 20).apply { cornerRadii = host.corners(20, 20, 20, 6) }
            // Подпись «JARVIS · 18:31» всегда в одну строку, даже под коротким ответом.
            minimumWidth = host.dp(176)
        }
        val who = host.rowLayout(8)
        // Последний ответ — живое ядро, следует за состоянием; старые — неподвижный кадр.
        val core = host.miniCore(skin, 26, live = last, mode = if (last) coreMode(host.assistantState) else ParticleCoreView.Mode.IDLE)
        if (last) lastCore = core
        who.addView(core)
        who.addView(host.monoLabel("JARVIS · ${hm(b.atMs)}", 9f, skin.muted).apply { maxLines = 1 }, lp(-2, -2))
        box.addView(who, lp(-2, -2))
        box.addView(host.label(b.text, 13.5f, skin.text, Fonts.body(host, 500)).apply { maxWidth = maxBubble() }, lp(-2, -2))
        // Трасса: настоящие вызовы инструментов между вопросом и этим ответом.
        val tools = if (askedAt == null) emptyList() else ActivityFeed.all()
            .filter { it.kind == ActivityFeed.Item.Kind.TOOL && it.atMs in askedAt..b.atMs }
            .map { it.title }.distinct().reversed()
        val took = askedAt?.let { b.atMs - it }?.takeIf { it in 1..MAX_TRACE_MS }
        val name = tools.joinToString(" → ").ifBlank { b.badge.orEmpty() }
        if (name.isNotBlank() || took != null) {
            val trace = host.rowLayout(6).apply {
                setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(4))
                background = host.glassBg(skin, 10, alpha(skin.text, .03f), alpha(skin.text, .07f)).apply { cornerRadius = host.dp(10).toFloat() }
            }
            trace.addView(host.label("⚙", 10f, skin.muted, Fonts.mono(host)))
            if (name.isNotBlank()) trace.addView(host.label(name, 9.5f, skin.accent, Fonts.mono(host, 500), lines = 1))
            if (took != null) trace.addView(host.label((if (name.isNotBlank()) "· " else "") + "%.2f s".format(Locale.US, took / 1000f), 9.5f, skin.muted, Fonts.mono(host)))
            box.addView(trace, lp(-2, -2))
        }
        return FrameLayout(host).apply { addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.START)) }
    }

    private fun maxBubble() = (host.resources.displayMetrics.widthPixels * .72f).toInt()

    private fun day(ms: Long): View {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        val same = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val name = if (same) "СЕГОДНЯ" else SimpleDateFormat("d MMMM", Locale("ru")).format(Date(ms)).uppercase()
        return host.monoLabel("$name · ${hm(ms)}", 10f, skin.metal).apply { gravity = Gravity.CENTER }
    }

    override fun onShow() = onData()

    override fun onData() {
        head.removeAllViews()
        head.addView(header("Диалог", false))
        list.removeAllViews()
        val items = ConversationStore.all().takeLast(MAX_ITEMS)
        if (items.isEmpty()) {
            list.addView(host.column(10).pad(4, 30).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(host.miniCore(skin, 140, live = true, mode = coreMode(host.assistantState)).also { lastCore = it })
                addView(host.label("Здесь будет ваш разговор с Джарвисом, голосом и текстом.", 14f, skin.muted, gravity = Gravity.CENTER))
            })
            return
        }
        var lastDay = -1
        var askedAt: Long? = null
        lastCore = null
        val lastAi = items.indexOfLast { it.role == ConversationStore.Bubble.Role.AI }
        items.forEachIndexed { index, b ->
            val d = Calendar.getInstance().apply { timeInMillis = b.atMs }.get(Calendar.DAY_OF_YEAR)
            if (d != lastDay) { list.addView(day(b.atMs), lp()); lastDay = d }
            when (b.role) {
                ConversationStore.Bubble.Role.USER -> { list.addView(mine(b)); askedAt = b.atMs }
                ConversationStore.Bubble.Role.AI -> { list.addView(his(b, askedAt, index == lastAi)); askedAt = null }
                else -> list.addView(host.monoLabel(b.text, 9.5f, skin.muted).apply { gravity = Gravity.CENTER }, lp())
            }
        }
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val MAX_ITEMS = 60
        const val MAX_TRACE_MS = 60_000L
        const val ARROW_UP = "M12 19V5M6 11l6-6 6 6"
    }
}

// ───────────────────────────── Память ─────────────────────────────

/** Живой граф памяти: узлы — факты, рёбра пульсируют. Подписаны только крупные узлы. */
class MemoryGraphView(context: Context, private val skin: Skin) : View(context) {
    private class Node(val x: Float, val y: Float, val r: Float, val label: String?, val phase: Float, val warm: Boolean)

    private var nodes: List<Node> = emptyList()
    private var edges: List<Pair<Int, Int>> = emptyList()
    private var labels: List<String> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.mono(context); textSize = context.dp(8.5f).toFloat() }
    private val still = Calm.noMotion(context)

    fun bind(facts: List<String>) {
        labels = facts
        layout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layout()

    private fun layout() {
        if (width == 0) return
        val rnd = Random(11)
        val big = labels.take(10)
        val total = (labels.size + 18).coerceAtMost(56)
        val d = context.dp(1).toFloat()
        nodes = List(total) { i ->
            Node(
                x = width * (.06f + .66f * rnd.nextFloat()),
                y = height * (.24f + .66f * rnd.nextFloat()),
                r = if (i < big.size) 4.5f * d else (1.4f + rnd.nextFloat() * 1.6f) * d,
                label = big.getOrNull(i)?.let { short(it) },
                phase = rnd.nextFloat() * 6f,
                warm = i == 2 && big.size > 2,
            )
        }
        val near = width * .2f
        edges = buildList {
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                if (hypot(nodes[i].x - nodes[j].x, nodes[i].y - nodes[j].y) < near && rnd.nextFloat() < .55f) add(i to j)
            }
        }
    }

    /** «Пользователь любит зелёный чай» → «любит зелёный чай». */
    private fun short(s: String): String {
        val words = s.split(' ').filter { it.isNotBlank() }
        val rest = if (words.firstOrNull()?.lowercase()?.startsWith("пользовател") == true) words.drop(1) else words
        val t = rest.joinToString(" ").lowercase()
        return if (t.length > 18) t.take(17) + "…" else t
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val d = context.dp(1).toFloat()
        val px = FloatArray(nodes.size * 2)
        nodes.forEachIndexed { i, n ->
            px[i * 2] = n.x + sin(t * .5f + n.phase) * 6 * d
            px[i * 2 + 1] = n.y + cos(t * .4f + n.phase) * 5 * d
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * .8f
        edges.forEachIndexed { k, (i, j) ->
            p.color = alpha(skin.accent, .05f + .12f * (.5f + .5f * sin(t * 1.5f + k)))
            c.drawLine(px[i * 2], px[i * 2 + 1], px[j * 2], px[j * 2 + 1], p)
        }
        p.style = Paint.Style.FILL
        nodes.forEachIndexed { i, n ->
            val col = if (n.warm) skin.hot else skin.accent
            val x = px[i * 2]; val y = px[i * 2 + 1]
            p.color = alpha(col, .12f); c.drawCircle(x, y, n.r * 3.5f, p)
            p.color = alpha(col, .22f); c.drawCircle(x, y, n.r * 2f, p)
            p.color = if (n.label != null) Color.WHITE else alpha(col, .85f)
            c.drawCircle(x, y, n.r, p)
            if (n.label != null) {
                txt.color = alpha(skin.text, .6f)
                c.drawText(n.label, x + n.r + 4 * d, y + 3 * d, txt)
            }
        }
        if (isShown && !still) postInvalidateDelayed(33L)
    }
}

/** Память премиум-стиля: граф, переключатель и строки фактов с кнопкой «забыть». */
class PremiumMemoryScreen(host: JarvisActivity) : Screen(host) {
    private val body = host.column(8)
    private val graph = MemoryGraphView(host, skin)
    private val count = host.label("0", 28f, skin.text, Fonts.logo(host))
    private val countSub = host.monoLabel("", 9f, skin.muted).apply { gravity = Gravity.END }
    private var tab = 0

    override fun meta(): String = "ХРАНИТСЯ ТОЛЬКО НА ТЕЛЕФОНЕ"

    override val view: View = host.column().apply {
        addView(header("Память", false))
        val inner = host.column(10)
        inner.addView(FrameLayout(host).apply {
            background = host.glassBg(skin, 22).apply { cornerRadius = host.dp(22).toFloat() }
            clipToOutline = true
            addView(graph, FrameLayout.LayoutParams(-1, -1))
            addView(host.monoLabel("ГРАФ ПАМЯТИ", 10f, skin.metal), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(host.dp(14), host.dp(12), 0, 0) })
            addView(host.column().apply {
                gravity = Gravity.END
                addView(count)
                addView(countSub)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, host.dp(10), host.dp(14), 0) })
        }, lp(h = host.dp(210)))
        inner.addView(host.segment(listOf("Факты", "Привычки", "Беседы"), tab) { tab = it; render() }.apply {
            setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(4))
            background = host.glassBg(skin, 16).apply { cornerRadius = host.dp(16).toFloat() }
        }, lp(h = host.dp(44)))
        inner.addView(body)
        addView(scroll(inner), lp(h = 0, weight = 1f))
    }

    private fun facts() = runCatching { BrainGraph.memory(host).all() }.getOrDefault(emptyList())

    override fun onShow() = render()

    private fun row(title: String, sub: String, tone: Int = skin.accent, trailing: View? = null, faded: Boolean = false): View {
        val r = host.rowLayout(12).apply {
            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
            background = host.glassBg(skin, 18).apply { cornerRadius = host.dp(18).toFloat() }
            if (faded) alpha = .6f
        }
        r.addView(host.glowDot(tone), lp(host.dp(6), host.dp(6)))
        r.addView(host.column(4).apply {
            addView(host.label(title, 13.5f, skin.text, Fonts.body(host, 600), lines = 3))
            addView(host.monoLabel(sub, 9.5f, skin.metal))
        }, lp(0, -2, 1f))
        trailing?.let { r.addView(it) }
        return r
    }

    private fun render() {
        val facts = facts()
        val talks = ConversationStore.all().filter { it.role == ConversationStore.Bubble.Role.USER }
        graph.bind(facts.map { it.text })
        count.text = facts.size.toString()
        countSub.text = "ФАКТОВ · ${talks.size} РЕПЛИК"
        body.removeAllViews()
        when (tab) {
            0 -> {
                if (facts.isEmpty()) body.addView(row("Фактов пока нет", "СКАЖИТЕ: «ЗАПОМНИ, ЧТО МОЙ ЛЮБИМЫЙ ЧАЙ ЗЕЛЁНЫЙ»", skin.metal))
                facts.sortedByDescending { it.createdAt }.forEachIndexed { i, f ->
                    val forget = host.monoLabel("ЗАБЫТЬ", 9f, skin.danger).apply {
                        setPadding(host.dp(8), host.dp(6), 0, host.dp(6))
                        tap {
                            host.showConfirm(f.text, "Забыть этот факт?") {
                                runCatching { BrainGraph.memory(host).remove(f.id) }
                                render()
                            }
                        }
                    }
                    val source = CATEGORY[f.category.lowercase()] ?: f.category.ifBlank { f.source }.uppercase()
                    body.addView(row(f.text, "$source · ${dm(f.createdAt)}", trailing = forget, faded = i >= FRESH))
                }
            }
            1 -> body.addView(row("Привычек пока нет", "ДЖАРВИС ЗАМЕТИТ ПОВТОРЫ И ТОЛЬКО ПРЕДЛОЖИТ", skin.hot))
            else -> {
                if (talks.isEmpty()) body.addView(row("Бесед пока нет", "ЗДЕСЬ БУДУТ ВАШИ ЗАПРОСЫ", skin.metal))
                talks.takeLast(30).reversed().forEach { body.addView(row(it.text, "ИЗ БЕСЕДЫ · ${dm(it.atMs)} ${hm(it.atMs)}")) }
            }
        }
        body.addView(host.monoLabel("ЧТОБЫ Я ЗАБЫЛ, СКАЖИТЕ: «ЗАБУДЬ ЭТО»", 9.5f, skin.metal).apply {
            gravity = Gravity.CENTER
            setPadding(0, host.dp(8), 0, 0)
        }, lp())
    }

    private companion object {
        const val FRESH = 3
        val CATEGORY = mapOf(
            "preference" to "ПРЕДПОЧТЕНИЕ", "fact" to "ФАКТ", "person" to "ЧЕЛОВЕК", "people" to "ЛЮДИ",
            "habit" to "ПРИВЫЧКА", "schedule" to "РАСПИСАНИЕ", "place" to "МЕСТО", "general" to "ОБЩЕЕ",
        )
    }
}

// ───────────────────────────── Навыки ─────────────────────────────

/** Навыки премиум-стиля: поиск, сетка модулей 2×N и список команд выбранного модуля. */
class PremiumSkillsScreen(host: JarvisActivity) : Screen(host) {
    private val groups = AssistantCapabilities.ALL.groupBy { it.category }
    private val grid = host.column(10)
    private val commands = host.column(8)
    private lateinit var scroller: ScrollView
    private var open: String? = null
    private var query = ""

    override fun meta(): String = "${AssistantCapabilities.ALL.size} КОМАНД · ${groups.size} МОДУЛЕЙ"

    override val view: View = host.column().apply {
        addView(header("Навыки", false))
        val inner = host.column(10)
        inner.addView(search())
        inner.addView(grid)
        inner.addView(commands)
        scroller = scroll(inner) as ScrollView
        addView(scroller, lp(h = 0, weight = 1f))
    }

    private fun search(): View {
        val row = host.rowLayout(10).apply {
            setPadding(host.dp(16), host.dp(4), host.dp(12), host.dp(4))
            background = host.glassBg(skin, 22).apply { cornerRadius = host.dp(22).toFloat() }
        }
        row.addView(IconView(host, SEARCH, skin.muted, 20))
        row.addView(EditText(host).apply {
            hint = "Найти навык"
            setHintTextColor(skin.muted)
            setTextColor(skin.text)
            textSize = 14f
            typeface = Fonts.body(host, 500)
            background = null
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); render() }
            })
        }, lp(0, host.dp(44), 1f))
        row.addView(host.monoLabel("ИЛИ СКАЖИТЕ", 9f, skin.metal).apply {
            setPadding(host.dp(7), host.dp(3), host.dp(7), host.dp(3))
            background = GradientDrawable().apply { cornerRadius = host.dp(8).toFloat(); setStroke(1, alpha(skin.text, .1f)) }
        })
        return row
    }

    private fun tile(name: String, example: String, n: Int, icon: String, beta: Boolean = false, onClick: () -> Unit): View {
        val selected = open == name
        val tone = if (beta) skin.violet else skin.text
        val box = FrameLayout(host).apply {
            background = if (beta) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(mix(skin.bg, skin.violet, .35f), .6f), alpha(skin.text, .03f))).apply {
                cornerRadius = host.dp(20).toFloat()
                setStroke(1, alpha(skin.violet, .25f))
            } else host.glassBg(skin, 20, line = if (selected) skin.accent else skin.line).apply { cornerRadius = host.dp(20).toFloat() }
            minimumHeight = host.dp(132)
            tap(onClick)
        }
        val col = host.column(8).pad(14)
        col.addView(FrameLayout(host).apply {
            background = GradientDrawable().apply {
                cornerRadius = host.dp(12).toFloat()
                setColor(alpha(tone, .06f))
                setStroke(1, alpha(tone, if (beta) .3f else .1f))
            }
            addView(IconView(host, icon, if (beta) skin.violet else skin.text, 20), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, lp(host.dp(38), host.dp(38)))
        col.addView(host.label(name, 15f, skin.text, Fonts.body(host, 600), lines = 1))
        col.addView(host.label(example, 10f, skin.muted, Fonts.mono(host), lines = 3).apply { setLineSpacing(0f, 1.3f) })
        box.addView(col, FrameLayout.LayoutParams(-1, -2))
        box.addView(
            if (beta) host.monoLabel("BETA", 9f, skin.violet).apply {
                setPadding(host.dp(7), host.dp(3), host.dp(7), host.dp(3))
                background = GradientDrawable().apply { cornerRadius = host.dp(8).toFloat(); setStroke(1, alpha(skin.violet, .4f)) }
            } else host.monoLabel("$n КОМ.", 9f, skin.metal),
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, host.dp(16), host.dp(14), 0) },
        )
        return box
    }

    private fun commandRow(c: AssistantCapabilities.Command): View {
        val example = c.examples.firstOrNull()?.removePrefix("джарвис, ").orEmpty()
        val safe = c.risk == AssistantCapabilities.RiskTag.SAFE || c.risk == AssistantCapabilities.RiskTag.ONLINE
        val r = host.rowLayout(12).apply {
            setPadding(host.dp(14), host.dp(11), host.dp(14), host.dp(11))
            background = host.glassBg(skin, 18).apply { cornerRadius = host.dp(18).toFloat() }
        }
        r.addView(host.column(4).apply {
            addView(host.label(c.title, 14f, skin.text, Fonts.body(host, 600)))
            if (example.isNotBlank()) addView(host.label("«$example»", 10.5f, skin.muted, Fonts.mono(host), lines = 2))
        }, lp(0, -2, 1f))
        if (!safe) r.addView(host.monoLabel("С ПОДТВ.", 9f, skin.warn))
        else if (example.isNotBlank()) {
            r.addView(IconView(host, Ic.SEND, skin.accent, 18))
            r.tap { host.send(example); host.show(JarvisActivity.CHAT) }
        }
        return r
    }

    override fun onShow() = render()

    private fun render() {
        grid.removeAllViews()
        commands.removeAllViews()
        if (query.isNotEmpty()) {
            grid.visibility = View.GONE
            val q = query.lowercase()
            val found = AssistantCapabilities.ALL.filter { c ->
                c.title.lowercase().contains(q) || c.category.lowercase().contains(q) || c.examples.any { it.lowercase().contains(q) }
            }
            commands.addView(host.monoLabel("НАЙДЕНО: ${found.size}", 10f, skin.metal))
            found.forEach { commands.addView(commandRow(it)) }
            return
        }
        grid.visibility = View.VISIBLE
        val tiles = groups.map { (name, list) ->
            val ex = list.firstOrNull()?.examples?.firstOrNull()?.removePrefix("джарвис, ").orEmpty()
            tile(name, "джарвис,\n«$ex»", list.size, ICONS[name] ?: Ic.GRID) {
                open = if (open == name) null else name
                render()
            }
        } + tile("Сделай сам", "сам нажимает\nв приложениях", 0, CODE, beta = true) {
            Sections.ALL.firstOrNull { it.title == "Мои команды" }?.let(host::openSection)
        }
        val names = groups.keys.toList()
        var expanded: View? = null
        tiles.chunked(2).forEachIndexed { r, pair ->
            val row = host.rowLayout(10).apply { gravity = Gravity.TOP }
            pair.forEach { row.addView(it, lp(0, -2, 1f)) }
            if (pair.size == 1) row.addView(View(host), lp(0, 1, 1f))
            grid.addView(row)
            // Открытый модуль раскрывается прямо под своей строкой, а не в конце экрана.
            val name = open
            if (name != null && names.indexOf(name) / 2 == r) {
                val list = host.column(8)
                list.addView(host.monoLabel("${name.uppercase()} · НАЖМИТЕ, ЧТОБЫ ПОПРОБОВАТЬ", 10f, skin.accent).apply { setPadding(host.dp(4), host.dp(2), 0, 0) })
                groups[name].orEmpty().forEach { list.addView(commandRow(it)) }
                grid.addView(list)
                expanded = row
            }
        }
        expanded?.let { row -> scroller.post { scroller.smoothScrollTo(0, (row.top + grid.top - host.dp(8)).coerceAtLeast(0)) } }
    }

    private companion object {
        val ICONS = mapOf(
            "Устройство" to Ic.SUN, "Приложения" to Ic.GRID, "Связь" to Ic.PHONE, "Музыка" to Ic.MUSIC,
            "Информация" to Ic.INFO, "Органайзер" to Ic.CAL, "Система" to Ic.CHIP, "Память" to Ic.BRAIN,
            "Автоматизация" to Ic.BOLT,
        )
        const val SEARCH = "M11 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12zM20 20l-4-4"
        const val CODE = "M9 4 4 9l5 5M15 10l5 5-5 5"
    }
}
