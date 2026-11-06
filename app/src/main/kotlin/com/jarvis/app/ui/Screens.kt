package com.jarvis.app.ui

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.jarvis.app.ActivityFeed
import com.jarvis.app.AndroidReminderBook
import com.jarvis.app.AssistantCapabilities
import com.jarvis.app.BrainGraph
import com.jarvis.app.ConversationStore
import com.jarvis.app.PrefsCustomRoutines
import com.jarvis.app.PrefsEventRoutines
import com.jarvis.app.PrefsNoteBook
import com.jarvis.app.VoiceController
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.notes.NoteParser
import com.jarvis.core.reminder.ReminderSpeech
import com.jarvis.core.runtime.LatencyStatsStore
import com.jarvis.screencontrol.JarvisNotificationListenerService
import com.jarvis.tools.CalendarTool
import com.jarvis.tools.WeatherTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Переключатель вкладок внутри экрана. */
fun JarvisActivity.segment(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val row = rowLayout(6)
    val items = ArrayList<TextView>()
    fun paint(sel: Int) = items.forEachIndexed { i, t ->
        t.setTextColor(if (i == sel) (if (skin.light) skin.onAccent else skin.text) else skin.muted)
        t.background = glassBg(skin, 18, if (i == sel) alpha(skin.accent, if (skin.light) 1f else .32f) else alpha(skin.bg, 0f), if (i == sel) skin.accent else skin.line)
    }
    options.forEachIndexed { i, o ->
        val t = label(o, 13f, skin.muted, gravity = Gravity.CENTER, lines = 1).apply {
            gravity = Gravity.CENTER
            tap { paint(i); onPick(i) }
        }
        items += t
        row.addView(t, lp(0, dp(36), 1f))
    }
    paint(selected)
    return row
}

class ChatScreen(host: JarvisActivity) : Screen(host) {
    private val list = host.column(10)
    private val scroller = scroll(list) as ScrollView
    private val input = EditText(host).apply {
        hint = "Написать Джарвису"
        setHintTextColor(skin.muted)
        setTextColor(skin.text)
        textSize = 15f
        typeface = Fonts.body(host)
        background = host.glassBg(skin, 22)
        setPadding(host.dp(16), 0, host.dp(16), 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, _, _ -> submit(); true }
    }

    override val view: View = host.column().apply {
        addView(header("Диалог", false))
        addView(scroller, lp(h = 0, weight = 1f))
        val row = host.rowLayout(8).pad(14, 6)
        row.addView(input, lp(0, host.dp(44), 1f))
        row.addView(FrameLayout(host).apply {
            background = host.glassBg(skin, 22, skin.accent, skin.accent)
            contentDescription = "Отправить"
            addView(IconView(host, Ic.SEND, skin.onAccent), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            tap { submit() }
        }, lp(host.dp(44), host.dp(44)))
        addView(row, lp().apply { bottomMargin = host.dp(30) })
    }

    private fun submit() {
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) return
        input.setText("")
        host.send(text)
    }

    private fun bubble(text: String, mine: Boolean): View {
        val t = host.label(text, 15f, if (mine && !skin.light) android.graphics.Color.WHITE else skin.text)
        t.setPadding(host.dp(13), host.dp(10), host.dp(13), host.dp(10))
        t.background = if (mine) host.glassBg(skin, 14, alpha(skin.accent, if (skin.light) .25f else .62f), skin.accent) else host.glassBg(skin, 14)
        t.maxWidth = (host.resources.displayMetrics.widthPixels * .82f).toInt()
        return FrameLayout(host).apply {
            addView(t, FrameLayout.LayoutParams(-2, -2, if (mine) Gravity.END else Gravity.START))
        }
    }

    override fun onShow() = onData()

    override fun onData() {
        list.removeAllViews()
        val items = ConversationStore.all()
        if (items.isEmpty()) list.addView(host.label("Здесь будет ваш разговор с Джарвисом, голосом и текстом.", 14f, skin.muted))
        items.takeLast(MAX_ITEMS).forEach { b ->
            list.addView(bubble(b.text, b.role == ConversationStore.Bubble.Role.USER))
        }
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object { const val MAX_ITEMS = 60 }
}

class MemoryScreen(host: JarvisActivity) : Screen(host) {
    private val body = host.column(10)
    private var tab = 1

    override val view: View = host.column().apply {
        addView(header("Память Jarvis", false))
        addView(host.segment(listOf("Беседы", "Факты", "Привычки"), tab) { tab = it; render() }.pad(14, 0), lp(h = host.dp(36)).apply { bottomMargin = host.dp(10) })
        addView(scroll(body), lp(h = 0, weight = 1f))
    }

    override fun onShow() = render()

    private fun render() {
        body.removeAllViews()
        when (tab) {
            0 -> {
                val list = ConversationStore.all().filter { it.role == ConversationStore.Bubble.Role.USER }.takeLast(30).reversed()
                if (list.isEmpty()) body.addView(host.label("Бесед пока нет.", 14f, skin.muted))
                list.forEach { body.addView(card(it.text, ConversationStore.formatTime(it.atMs))) }
            }
            1 -> {
                val facts = runCatching { BrainGraph.memory(host).all() }.getOrDefault(emptyList())
                if (facts.isEmpty()) body.addView(host.label("Фактов пока нет. Скажите: «запомни, что мой любимый чай зелёный».", 14f, skin.muted))
                facts.forEach { f ->
                    body.addView(card(f.text, f.category, trailing = host.label("Забыть", 13f, skin.danger).apply {
                        tap {
                            host.showConfirm(f.text, "Забыть этот факт?") {
                                runCatching { BrainGraph.memory(host).remove(f.id) }
                                render()
                            }
                        }
                    }))
                }
            }
            else -> body.addView(host.label("Здесь появятся повторяющиеся действия. Джарвис только предлагает, сам ничего не создаёт.", 14f, skin.muted))
        }
        body.addView(card("Чтобы я что-то забыл, скажите: «Забудь это».", icon = Ic.INFO))
    }
}

class SkillsScreen(host: JarvisActivity) : Screen(host) {
    private val body = host.column(10)
    private val open = HashSet<String>()

    override val view: View = host.column().apply {
        addView(header("Навыки Jarvis", false))
        addView(scroll(body), lp(h = 0, weight = 1f))
    }

    override fun onShow() = render()

    private fun render() {
        body.removeAllViews()
        AssistantCapabilities.ALL.groupBy { it.category }.forEach { (category, commands) ->
            body.addView(card(category, commands.take(3).joinToString(", ") { it.title }, ICONS[category] ?: Ic.GRID) {
                if (!open.add(category)) open.remove(category)
                render()
            })
            if (category in open) commands.forEach { c ->
                val example = c.examples.firstOrNull()?.removePrefix("джарвис, ").orEmpty()
                val safe = c.risk == AssistantCapabilities.RiskTag.SAFE || c.risk == AssistantCapabilities.RiskTag.ONLINE
                body.addView(card(c.title, "«$example»", onClick = if (safe && example.isNotBlank()) ({ host.send(example); host.show(JarvisActivity.CHAT) }) else null))
            }
        }
    }

    private companion object {
        val ICONS = mapOf("Устройство" to Ic.SUN, "Приложения" to Ic.GRID, "Связь" to Ic.PHONE, "Музыка" to Ic.MUSIC, "Информация" to Ic.INFO)
    }
}

class RoutinesScreen(host: JarvisActivity) : Screen(host) {
    private val body = host.column(10)
    private var tab = 0

    override val view: View = host.column().apply {
        addView(header("Рутины", true))
        addView(host.segment(listOf("Мои", "Рекомендации", "Дела"), tab) { tab = it; render() }.pad(14, 0), lp(h = host.dp(36)).apply { bottomMargin = host.dp(10) })
        addView(scroll(body), lp(h = 0, weight = 1f))
    }

    override fun onShow() = render()

    private fun run(phrase: String) = host.label("Запустить", 12f, skin.onAccent, Fonts.body(host, 700)).apply {
        background = host.glassBg(skin, 14, skin.accent, skin.accent)
        setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
        tap { host.send(phrase); host.show(JarvisActivity.HOME) }
    }

    private fun render() {
        body.removeAllViews()
        when (tab) {
            0 -> {
                BUILT_IN.forEach { (title, sub, phrase) -> body.addView(card(title, sub, Ic.CLOCK, run(phrase))) }
                val own = PrefsCustomRoutines(host)
                val events = PrefsEventRoutines(host)
                val rules = events.rules().filter { own.steps(it.name) != null }
                val ruleNames = rules.map { it.name }.toSet()
                own.names().filter { it !in ruleNames }.forEach { name ->
                    body.addView(card(name, own.steps(name).orEmpty().joinToString(", "), Ic.BOLT, run(name)))
                }
                if (rules.isNotEmpty()) body.addView(section("По событиям"))
                rules.forEach { rule ->
                    val remove = host.label("Удалить", 12f, skin.text, Fonts.body(host, 700)).apply {
                        background = host.glassBg(skin, 14)
                        setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                        tap { events.remove(rule); own.remove(rule.name); render() }
                    }
                    body.addView(card(rule.name, own.steps(rule.name).orEmpty().joinToString(", "), Ic.BOLT, remove))
                }
                body.addView(card("Создать рутину", "Скажите: «когда я говорю поехали, включи блютуз и музыку» или «когда подключаю наушники, включи музыку»", Ic.INFO))
            }
            1 -> body.addView(host.label("Пока нет рекомендаций. Когда Джарвис заметит повторяющиеся действия, предложит их здесь.", 14f, skin.muted))
            else -> tasks()
        }
    }

    private fun tasks() {
        val now = LocalDateTime.now()
        Device.nextAlarm(host)?.let {
            val at = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
            body.addView(card("Ближайший будильник", ReminderSpeech.whenPhrase(at, now), Ic.CLOCK))
        }
        runCatching { AndroidReminderBook(host).all() }.getOrDefault(emptyList()).sortedBy { it.at }.forEach {
            body.addView(card(it.text, ReminderSpeech.whenPhrase(it.at, now), Ic.BELL))
        }
        val notes = PrefsNoteBook(host)
        listOf(NoteParser.SHOPPING to "Покупки", NoteParser.TODO to "Дела", NoteParser.NOTES to "Заметки").forEach { (key, title) ->
            val items = notes.items(key)
            if (items.isNotEmpty()) body.addView(card(title, items.joinToString(", "), Ic.CAL))
        }
        if (body.childCount == 0) body.addView(host.label("Дел пока нет. Скажите: «напомни завтра в девять позвонить маме».", 14f, skin.muted))
    }

    private companion object {
        val BUILT_IN = listOf(
            Triple("Режим сна", "Музыка, фонарик, яркость, тишина", "режим сна"),
            Triple("Режим кино", "Яркость 30%, тишина", "режим кино"),
            Triple("Режим работы", "Тишина и никакой музыки", "режим работы"),
            Triple("Я ухожу", "Музыка и фонарик выключаются", "я ухожу"),
            Triple("Я проснулся", "Сводка и обычный режим", "я проснулся"),
        )
    }
}

class StatusScreen(host: JarvisActivity) : Screen(host) {
    private val cpu = RingView(host, host.skin, "Процессор")
    private val ram = RingView(host, host.skin, "Память")
    private val bat = RingView(host, host.skin, "Заряд")
    private val values = HashMap<String, TextView>()
    private val services = host.column()
    private val tick = object : Runnable {
        override fun run() { refresh(); view.postDelayed(this, TICK_MS) }
    }

    private fun kv(box: LinearLayout, name: String) {
        val v = host.label("", 14f, skin.accent, Fonts.display(host))
        values[name] = v
        box.addView(host.rowLayout().pad(12, 10).apply {
            addView(host.label(name, 14f, skin.text), lp(0, -2, 1f))
            addView(v)
        })
    }

    override val view: View = host.column().apply {
        addView(header("Система Jarvis", true))
        val body = host.column(10)
        val rings = host.rowLayout(8).pad(12).apply { background = host.glassBg(skin) }
        listOf(cpu, ram, bat).forEach { rings.addView(it, lp(0, host.dp(92), 1f)) }
        body.addView(rings)
        val table = host.column().apply { background = host.glassBg(skin) }
        listOf("Температура", "Сеть", "Свободно", "Ответ, среднее").forEach { kv(table, it) }
        body.addView(table)
        body.addView(section("Службы"))
        body.addView(services.apply { background = host.glassBg(skin) })
        addView(scroll(body), lp(h = 0, weight = 1f))
    }

    private fun refresh() {
        cpu.percent = Device.cpuPercent()
        ram.percent = Device.ramPercent(host)
        bat.percent = Device.battery(host)
        values["Температура"]?.text = "${Device.temperature(host)}°C"
        values["Сеть"]?.text = Device.network(host)
        values["Свободно"]?.text = "${Device.freeStorageGb()} ГБ"
        values["Ответ, среднее"]?.text = LatencyStatsStore.summaryLines().firstOrNull()?.substringAfter(':', "")?.trim()?.take(14)?.ifBlank { "—" } ?: "—"
        services.removeAllViews()
        val on = host.isOn
        listOf(
            "Голосовая служба" to (if (on) "работает" else "выключена"),
            "Голос" to (if (VoiceController.isRunning) "модель загружена" else "не загружен"),
            "Режим Live" to (if (VoiceController.liveTalk) "идёт разговор" else "выключен"),
        ).forEach { (name, state) ->
            services.addView(host.rowLayout().pad(12, 9).apply {
                addView(host.label(name, 14f, skin.text), lp(0, -2, 1f))
                addView(host.label(state, 12f, if (on) skin.good else skin.muted))
            })
        }
    }

    override fun onShow() { view.removeCallbacks(tick); view.post(tick) }
    override fun onHide() { view.removeCallbacks(tick) }

    private companion object { const val TICK_MS = 2000L }
}

class ScanScreen(host: JarvisActivity) : Screen(host) {
    private val weatherNow = host.label("…", 13f, skin.text, Fonts.body(host, 600))
    private val eventsText = host.label("…", 13f, skin.text, Fonts.body(host, 600))
    private val notifText = host.label("…", 13f, skin.text, Fonts.body(host, 600))
    private val today = host.column(10)

    private fun chip(value: TextView, name: String) = host.column().pad(10, 7).apply {
        background = host.glassBg(skin, 12)
        addView(value)
        addView(host.label(name, 12f, skin.muted))
    }

    override val view: View = host.column().apply {
        addView(header("Анализ ситуации", true))
        val body = host.column(10)
        val top = host.rowLayout(8)
        top.addView(GlobeView(host, skin), lp(0, host.dp(220), 1f))
        val chips = host.column(6)
        chips.addView(chip(weatherNow, "Погода"))
        chips.addView(chip(eventsText, "Календарь"))
        chips.addView(chip(notifText, "Уведомления"))
        top.addView(chips, lp(host.dp(140), -2))
        body.addView(top)
        body.addView(today)
        addView(scroll(body), lp(h = 0, weight = 1f))
        body.addView(host.button("Сводка дня голосом", true) { host.send("сводка на день"); host.show(JarvisActivity.HOME) }, lp(h = host.dp(48)))
    }

    override fun onShow() {
        val notifications = runCatching { JarvisNotificationListenerService.snapshot() }.getOrDefault(emptyList())
            .count { it.packageName != host.packageName && it.title.isNotBlank() }
        notifText.text = "$notifications новых"
        host.lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { tool { WeatherTool(host).execute(call("weather", emptyMap())).message } }
            val week = withContext(Dispatchers.IO) { tool { WeatherTool(host).execute(call("weather", mapOf("week" to true))).message } }
            val cal = withContext(Dispatchers.IO) { tool { CalendarTool(host).execute(call("calendar", mapOf("action" to "list", "day" to 0))).message } }
            weatherNow.text = Regex("сейчас (-?\\d+°)").find(now)?.groupValues?.get(1) ?: "нет данных"
            val empty = cal.isBlank() || cal.contains("нет", ignoreCase = true)
            eventsText.text = if (empty) "свободно" else "есть события"
            today.removeAllViews()
            today.addView(card("На сегодня", if (empty) "Событий в календаре нет" else cal.removePrefix("События: "), Ic.CAL))
            if (week.isNotBlank()) today.addView(card("Неделя", week, Ic.SUN))
        }
    }

    private fun call(id: String, args: Map<String, Any>) = ToolCall(requestId = "ui", toolId = id, arguments = args)
    private fun tool(block: () -> String?): String = runCatching(block).getOrNull().orEmpty()
}
